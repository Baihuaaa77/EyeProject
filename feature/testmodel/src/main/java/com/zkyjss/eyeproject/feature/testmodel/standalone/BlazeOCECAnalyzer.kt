package com.zkyjss.eyeproject.feature.testmodel.standalone

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.zkyjss.eyeproject.core.model.BoundingBox
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.File
import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.sqrt

/** 单眼结果：眼框（正立源图坐标）+ 睁眼概率。 */
data class StandaloneEyeResult(
    val box: BoundingBox,
    val probOpen: Float
)

/** 逐帧结果（全替换管线：BlazeFace 检测 + OCEC 分类，无 MediaPipe）。 */
data class StandaloneFrameResult(
    val faceBox: BoundingBox?,
    val rightEye: StandaloneEyeResult?,
    val leftEye: StandaloneEyeResult?,
    val blinkCount: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMillis: Long,
    val detectionLatencyMillis: Long,
    val classificationLatencyMillis: Long,
    val fps: Float
) {
    val faceDetected: Boolean get() = faceBox != null
}

/**
 * 全替换眨眼管线分析器：BlazeFace short-range 人脸检测（128×128 tflite）+ OCEC-P 睁闭眼分类（24×40 tflite），
 * 完全不依赖 MediaPipe。运行时 LiteRT（org.tensorflow.lite.Interpreter）。
 *
 * 解码规格（按 MediaPipe 官方 face_detection.pbtxt + TensorsToDetections/SsdAnchors 源码实现，
 * 并已用官方 FaceDetector 输出对齐验证，PC 端残差 <0.003 归一化）：
 * - 输入 128×128 letterbox（保比例 + BORDER_ZERO 填充）、RGB、[-1,1] 归一化、NHWC
 * - 896 anchors = 4 层（strides 8/16/16/16）网格中心 (x+0.5)/grid，fixed_anchor_size → 尺寸恒为 1.0（不参与解码）
 * - 框：center = anchor_c + raw[0..1]/128，w/h = raw[2..3]/128（线性，无 exp）；reverse_output_order → raw[0]=x
 * - 6 关键点：kpt_c = anchor_c + raw[4+2k]/128（kpt0=右眼, kpt1=左眼）
 * - score = sigmoid(classificators)
 * - OCEC 输入 24×40 RGB /255（注意与 BlazeFace 的 [-1,1] 归一化不同）
 */
class StandaloneBlinkAnalyzer(
    context: Context,
    private val onResult: (StandaloneFrameResult) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val appContext: Context = context.applicationContext

    private val blaze: Interpreter = Interpreter(loadModel(context, BLAZE_MODEL))
    private val ocec: Interpreter = Interpreter(loadModel(context, OCEC_MODEL))

    // ---- 输入/输出缓冲（复用，逐帧零大分配） ----
    private val blazeIn: ByteBuffer =
        ByteBuffer.allocateDirect(1 * BLAZE_SIZE * BLAZE_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val blazeBoxOut: ByteBuffer =
        ByteBuffer.allocateDirect(1 * NUM_BOXES * NUM_COORDS * 4).order(ByteOrder.nativeOrder())
    private val blazeClsOut: ByteBuffer =
        ByteBuffer.allocateDirect(1 * NUM_BOXES * 1 * 4).order(ByteOrder.nativeOrder())
    private val ocecIn: ByteBuffer =
        ByteBuffer.allocateDirect(1 * OCEC_H * OCEC_W * 3 * 4).order(ByteOrder.nativeOrder())
    private val ocecOut: ByteBuffer =
        ByteBuffer.allocateDirect(1 * 4).order(ByteOrder.nativeOrder())

    // 复用位图（letterbox 画布 / 眼片目标尺寸）
    private val blazeBitmap = Bitmap.createBitmap(BLAZE_SIZE, BLAZE_SIZE, Bitmap.Config.ARGB_8888)
    private val blazeCanvas = Canvas(blazeBitmap)
    private val blazePixels = IntArray(BLAZE_SIZE * BLAZE_SIZE)
    private val eyeBitmap = Bitmap.createBitmap(OCEC_W, OCEC_H, Bitmap.Config.ARGB_8888)
    private val eyeCanvas = Canvas(eyeBitmap)
    private val eyePixels = IntArray(OCEC_W * OCEC_H)

    // 896 个 anchor 中心 (cy, cx) 交错存储，归一化坐标
    private val anchorCyCx: FloatArray = buildAnchorCenters()

    // ---- fps（结果侧 1s 滚动窗口，与 blink 页口径一致） ----
    private val resultNanos = ArrayDeque<Long>()

    // ---- 眨眼状态机（概率版，逻辑同 blink 页 EAR：连续 3 帧闭合、回升计数） ----
    private var closedFrameCount = 0
    private var isClosed = false
    var blinkCount = 0
        private set

    /** 调试开关：录制期间按采样率把眼片原图存到外部私有目录（PC 端验证分类用）。 */
    @Volatile
    var dumpCrops: Boolean = false
    private var dumpCountRight = 0
    private var dumpCountLeft = 0
    private var cropsDir: File? = null

    private var frameIndex = 0

    override fun analyze(imageProxy: ImageProxy) {
        frameIndex += 1
        if (frameIndex % DETECTION_FRAME_INTERVAL != 0) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val sourceWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageProxy.height
        } else {
            imageProxy.width
        }
        val sourceHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageProxy.width
        } else {
            imageProxy.height
        }

        try {
            var stage = "preprocess(位图转换/旋转)"
            val upright: Bitmap = try {
                rotateBitmap(toBitmap(imageProxy), rotationDegrees)
            } catch (t: Throwable) {
                throw RuntimeException(stage, t)
            }

            // ---- BlazeFace 检测 ----
            stage = "detect(LiteRT 推理)"
            var detectionLatencyMs = 0L
            val face: DecodedFace? = try {
                fillBlazeInput(upright)
                val t0 = System.nanoTime()
                // 输出缓冲每次 run 前必须复位：上一帧读数把 position 推到了末尾，不 clear 会 BufferOverflow
                blazeBoxOut.clear()
                blazeClsOut.clear()
                blaze.runForMultipleInputsOutputs(
                    arrayOf<Any>(blazeIn),
                    mapOf(0 to blazeBoxOut as Any, 1 to blazeClsOut)
                )
                detectionLatencyMs = (System.nanoTime() - t0) / 1_000_000L
                blazeBoxOut.rewind()
                blazeClsOut.rewind()
                decodeBestFace(detectionLatencyMs)
            } catch (t: Throwable) {
                throw RuntimeException(stage, t)
            }

            // ---- OCEC 双眼分类 ----
            var rightEye: StandaloneEyeResult? = null
            var leftEye: StandaloneEyeResult? = null
            var classificationLatencyMs = 0L
            if (face != null) {
                stage = "classify(OCEC 推理)"
                try {
                    val t1 = System.nanoTime()
                    rightEye = classifyEye(upright, face, KEYPOINT_RIGHT_EYE)
                    leftEye = classifyEye(upright, face, KEYPOINT_LEFT_EYE)
                    classificationLatencyMs = (System.nanoTime() - t1) / 1_000_000L
                } catch (t: Throwable) {
                    throw RuntimeException(stage, t)
                }
            }

            // ---- 眨眼状态机（双眼平均概率） ----
            val probs = listOfNotNull(rightEye?.probOpen, leftEye?.probOpen)
            val avgProb = if (probs.isEmpty()) null else probs.average().toFloat()
            updateBlinkState(avgProb)

            // ---- fps（结果侧 1s 滚动窗口） ----
            val now = System.nanoTime()
            resultNanos.addLast(now)
            val windowStart = now - FPS_WINDOW_NANOS
            while (resultNanos.isNotEmpty() && resultNanos.first() < windowStart) {
                resultNanos.removeFirst()
            }

            onResult(
                StandaloneFrameResult(
                    faceBox = face?.toSourceBox(),
                    rightEye = rightEye,
                    leftEye = leftEye,
                    blinkCount = blinkCount,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    timestampMillis = System.currentTimeMillis(),
                    detectionLatencyMillis = detectionLatencyMs,
                    classificationLatencyMillis = classificationLatencyMs,
                    fps = resultNanos.size.toFloat()
                )
            )
        } catch (throwable: Throwable) {
            onError(throwable)
        } finally {
            imageProxy.close()
        }
    }

    /** 重置眨眼计数。 */
    fun resetBlink() {
        closedFrameCount = 0
        isClosed = false
        blinkCount = 0
    }

    private fun updateBlinkState(avgProb: Float?) {
        if (avgProb == null) return  // 无脸帧不更新（与 EAR 空值口径一致）
        if (avgProb < OPEN_PROB_THRESHOLD) {
            closedFrameCount++
            if (closedFrameCount >= CONSECUTIVE_CLOSED_FRAMES) isClosed = true
        } else {
            if (isClosed) blinkCount++
            isClosed = false
            closedFrameCount = 0
        }
    }

    // ---- BlazeFace 前处理：letterbox 到 128×128，RGB [-1,1] NHWC ----

    private var letterboxScale = 1f
    private var letterboxDx = 0f
    private var letterboxDy = 0f

    private fun fillBlazeInput(bitmap: Bitmap) {
        val scale = BLAZE_SIZE / maxOf(bitmap.width, bitmap.height).toFloat()
        val nw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val nh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        letterboxScale = scale
        letterboxDx = (BLAZE_SIZE - nw) / 2f
        letterboxDy = (BLAZE_SIZE - nh) / 2f

        blazeCanvas.drawColor(android.graphics.Color.BLACK)
        blazeCanvas.drawBitmap(
            bitmap, null,
            RectF(letterboxDx, letterboxDy, letterboxDx + nw, letterboxDy + nh),
            null
        )
        blazeBitmap.getPixels(blazePixels, 0, BLAZE_SIZE, 0, 0, BLAZE_SIZE, BLAZE_SIZE)

        blazeIn.rewind()
        for (i in 0 until BLAZE_SIZE * BLAZE_SIZE) {
            val px = blazePixels[i]
            // ARGB int → RGB，归一化 [-1,1]
            blazeIn.putFloat(((px shr 16) and 0xff) / 127.5f - 1f)
            blazeIn.putFloat(((px shr 8) and 0xff) / 127.5f - 1f)
            blazeIn.putFloat((px and 0xff) / 127.5f - 1f)
        }
    }

    // ---- 解码：取最高分人脸 ----

    private class DecodedFace(
        // 全部为 128 空间归一化坐标
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float,
        val rightEyeX: Float, val rightEyeY: Float,
        val leftEyeX: Float, val leftEyeY: Float,
        // 反映射用的 letterbox 参数
        val letterboxScale: Float, val letterboxDx: Float, val letterboxDy: Float
    ) {
        fun toSourceBox(): BoundingBox = BoundingBox(
            left = denormX(x1), top = denormY(y1), right = denormX(x2), bottom = denormY(y2)
        )

        fun denormX(v: Float): Float = (v * BLAZE_SIZE - letterboxDx) / letterboxScale
        fun denormY(v: Float): Float = (v * BLAZE_SIZE - letterboxDy) / letterboxScale
    }

    private fun decodeBestFace(detLatencyMs: Long): DecodedFace? {
        var best = -1
        var bestScore = MIN_SCORE_THRESHOLD
        for (i in 0 until NUM_BOXES) {
            val logit = blazeClsOut.getFloat(i * 4)
            val score = 1f / (1f + exp(-logit.coerceIn(-100f, 100f)))
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return null

        val base = best * NUM_COORDS
        val cy = anchorCyCx[best * 2]
        val cx = anchorCyCx[best * 2 + 1]
        // reverse_output_order: raw = [x_center, y_center, w, h] 偏移；fixed anchor 尺寸 → /128 后直接加 anchor 中心
        val xCenter = cx + blazeBoxOut.getFloat((base + 0) * 4) / BLAZE_SIZE
        val yCenter = cy + blazeBoxOut.getFloat((base + 1) * 4) / BLAZE_SIZE
        val w = blazeBoxOut.getFloat((base + 2) * 4) / BLAZE_SIZE
        val h = blazeBoxOut.getFloat((base + 3) * 4) / BLAZE_SIZE

        fun keypointX(k: Int): Float =
            cx + blazeBoxOut.getFloat((base + 4 + k * 2) * 4) / BLAZE_SIZE
        fun keypointY(k: Int): Float =
            cy + blazeBoxOut.getFloat((base + 5 + k * 2) * 4) / BLAZE_SIZE

        return DecodedFace(
            x1 = xCenter - w / 2f, y1 = yCenter - h / 2f,
            x2 = xCenter + w / 2f, y2 = yCenter + h / 2f,
            score = bestScore,
            rightEyeX = keypointX(KEYPOINT_RIGHT_EYE), rightEyeY = keypointY(KEYPOINT_RIGHT_EYE),
            leftEyeX = keypointX(KEYPOINT_LEFT_EYE), leftEyeY = keypointY(KEYPOINT_LEFT_EYE),
            letterboxScale = letterboxScale, letterboxDx = letterboxDx, letterboxDy = letterboxDy
        )
    }

    // ---- OCEC 眼片分类：眼点中心 + 脸框比例的几何眼框，margin 外扩，resize 24×40，RGB /255 NHWC ----

    private fun classifyEye(
        bitmap: Bitmap,
        face: DecodedFace,
        keypointIndex: Int
    ): StandaloneEyeResult? {
        // 眼点先转源图坐标（128 归一化 → 源图像素），与脸宽同一空间再算几何眼框
        val exN = if (keypointIndex == KEYPOINT_RIGHT_EYE) face.rightEyeX else face.leftEyeX
        val eyN = if (keypointIndex == KEYPOINT_RIGHT_EYE) face.rightEyeY else face.leftEyeY
        val ex = face.denormX(exN)
        val ey = face.denormY(eyN)
        val fx1 = face.denormX(face.x1)
        val fy1 = face.denormY(face.y1)
        val faceWidth = face.denormX(face.x2) - fx1
        if (faceWidth <= 0f) return null

        // 几何眼框（2026-09-11 真机眼片几何扫描标定）：中心 = 眼点略下移（BlazeFace 眼点高于视觉眼中心），
        // 紧框——训练分布是零 margin 的检测框（眼睛占满画幅），宽松框含眉毛/镜框会致域外误判
        // 扫描证据：zoom0.45+下移0.1 使 50 张真机睁眼片全部高置信（mean 0.981，0 误判；旧参数 0.605/34%）
        val ew = faceWidth * EYE_WIDTH_RATIO
        val eh = faceWidth * EYE_HEIGHT_RATIO
        val eyeCy = ey + faceWidth * EYE_CENTER_DY_RATIO
        val x1 = (ex - ew / 2f).toInt().coerceAtLeast(0)
        val y1 = (eyeCy - eh / 2f).toInt().coerceAtLeast(0)
        val x2 = (ex + ew / 2f).toInt().coerceAtMost(bitmap.width - 1)
        val y2 = (eyeCy + eh / 2f).toInt().coerceAtMost(bitmap.height - 1)
        if (x2 - x1 < 2 || y2 - y1 < 2) return null

        val rect = android.graphics.Rect(x1, y1, x2, y2)
        eyeCanvas.drawColor(android.graphics.Color.BLACK)
        eyeCanvas.drawBitmap(bitmap, rect, RectF(0f, 0f, OCEC_W.toFloat(), OCEC_H.toFloat()), null)
        eyeBitmap.getPixels(eyePixels, 0, OCEC_W, 0, 0, OCEC_W, OCEC_H)

        ocecIn.rewind()
        for (i in 0 until OCEC_H * OCEC_W) {
            val px = eyePixels[i]
            // RGB /255（OCEC 归一化口径，注意与 BlazeFace 的 [-1,1] 不同）
            ocecIn.putFloat(((px shr 16) and 0xff) / 255f)
            ocecIn.putFloat(((px shr 8) and 0xff) / 255f)
            ocecIn.putFloat((px and 0xff) / 255f)
        }
        // 输出缓冲每次 run 前复位（同 blaze，防 BufferOverflow）
        ocecOut.clear()
        ocec.run(ocecIn, ocecOut)
        ocecOut.rewind()
        val probOpen = ocecOut.float

        // 调试采样：录制期间每眼每 15 次 classify 存一张眼片原图（带概率命名），供 PC 端对比验证
        val dumpHit = if (keypointIndex == KEYPOINT_LEFT_EYE) {
            dumpCountLeft++ % 15 == 0
        } else {
            dumpCountRight++ % 15 == 0
        }
        if (dumpCrops && dumpHit) {
            try {
                val dir = cropsDir ?: File(appContext.getExternalFilesDir(null), "crops")
                    .also { it.mkdirs() }.also { cropsDir = it }
                val tag = if (keypointIndex == KEYPOINT_LEFT_EYE) "left" else "right"
                val crop = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
                File(dir, "${tag}_p%.3f_%d.png".format(probOpen, System.currentTimeMillis()))
                    .outputStream().use { out -> crop.compress(Bitmap.CompressFormat.PNG, 100, out) }
                crop.recycle()
            } catch (_: Throwable) {
                // 调试采样失败不影响主链路
            }
        }

        return StandaloneEyeResult(
            box = BoundingBox(left = x1.toFloat(), top = y1.toFloat(), right = x2.toFloat(), bottom = y2.toFloat()),
            probOpen = probOpen
        )
    }

    // ---- 公共工具 ----

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width
        val bitmapWidth = imageProxy.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, imageProxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun close() {
        blaze.close()
        ocec.close()
    }

    companion object {
        private const val BLAZE_MODEL = "blaze_face_short_range.tflite"
        private const val OCEC_MODEL = "ocec_p.tflite"
        private const val BLAZE_SIZE = 128
        private const val NUM_BOXES = 896
        private const val NUM_COORDS = 16
        private const val OCEC_W = 40
        private const val OCEC_H = 24
        private const val KEYPOINT_RIGHT_EYE = 0
        private const val KEYPOINT_LEFT_EYE = 1
        private const val MIN_SCORE_THRESHOLD = 0.5f
        private const val OPEN_PROB_THRESHOLD = 0.5f
        private const val CONSECUTIVE_CLOSED_FRAMES = 3
        private const val DETECTION_FRAME_INTERVAL = 1
        private const val FPS_WINDOW_NANOS = 1_000_000_000L

        // 几何眼框参数（人体测量学标定，2026-09-11）：
        // 睑裂宽 ≈ 0.20-0.22 × 面宽（经典五眼法：面宽=5 个睑裂宽），框覆盖全睑裂+15% 余量 → 0.24
        // 睑裂高 ≈ 1/3 睑裂宽，框高含余量 → 0.11；框中心 = 眼点（瞳孔）微下移 0.005
        // 与模型紧框偏好存在张力（训练为零 margin 检测框），残余姿态漂移靠微调几何增强解决
        private const val EYE_WIDTH_RATIO = 0.24f
        private const val EYE_HEIGHT_RATIO = 0.11f
        private const val EYE_CENTER_DY_RATIO = 0.005f

        private fun loadModel(context: Context, name: String): MappedByteBuffer {
            context.assets.openFd(name).use { fd ->
                fd.createInputStream().use { input ->
                    val channel = input.channel
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        /**
         * 896 个 anchor 中心 (cy, cx)，归一化坐标。
         * 生成规则（MediaPipe SsdAnchorsCalculator，face_detection_short_range 配置）：
         * 4 层 strides [8,16,16,16]；同 stride 层合并，每格 anchor 顺序 = 各层 regular 后接 interpolated：
         * 层 0（grid 16×16）：[s0, sqrt(s0·s1)]；层 1-3 合并（grid 8×8）：[s1, sqrt(s1·s2), s2, sqrt(s2·s3), s3, sqrt(s3·1)]
         * s_k = 0.1484375 + (0.75 - 0.1484375) × k / 3；fixed_anchor_size → 尺寸恒 1.0，不参与解码，只存中心。
         */
        private fun buildAnchorCenters(): FloatArray {
            val out = FloatArray(NUM_BOXES * 2)
            fun scale(k: Int): Float = MIN_SCALE_ANCHOR + (MAX_SCALE_ANCHOR - MIN_SCALE_ANCHOR) * k / 3f
            var idx = 0
            var layerId = 0
            while (layerId < 4) {
                var lastSame = layerId
                while (lastSame < 4 && STRIDES[lastSame] == STRIDES[layerId]) lastSame++
                val grid = BLAZE_SIZE / STRIDES[layerId]
                for (y in 0 until grid) {
                    for (x in 0 until grid) {
                        for (k in layerId until lastSame) {
                            out[idx++] = (y + 0.5f) / grid
                            out[idx++] = (x + 0.5f) / grid
                            val sNext = if (k == 3) 1f else scale(k + 1)
                            val sInterp = sqrt(scale(k) * sNext)
                            out[idx++] = (y + 0.5f) / grid
                            out[idx++] = (x + 0.5f) / grid
                        }
                    }
                }
                layerId = lastSame
            }
            check(idx == NUM_BOXES * 2) { "anchor count mismatch: $idx" }
            return out
        }

        private const val MIN_SCALE_ANCHOR = 0.1484375f
        private const val MAX_SCALE_ANCHOR = 0.75f
        private val STRIDES = intArrayOf(8, 16, 16, 16)
    }
}
