package com.zkyjss.eyeproject.feature.testmodel.blink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.math.hypot

/** 单只眼睛的关键点：EAR 6 点（p1..p6）+ 虹膜 5 点（中心 + 4 边界），像素坐标。 */
data class BlinkEyeLandmarks(
    val earPoints: List<Point2f>,
    val irisPoints: List<Point2f>,
    val irisDiameterPx: Float
)

/** 逐帧结果：人脸框 + 双眼关键点 + 帧信息。 */
data class BlinkFrameResult(
    val faceBox: BoundingBox?,
    val leftEye: BlinkEyeLandmarks?,
    val rightEye: BlinkEyeLandmarks?,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMillis: Long,
    val detectionLatencyMillis: Long,
    val fps: Float
)

/**
 * MediaPipe FaceLandmarker（478 点）逐帧分析器，眨眼测试页专用。
 * 本模块独立实现（实验页不依赖 core:camera），输出只保留眼部检测所需字段。
 */
class BlinkLandmarkerAnalyzer(
    context: Context,
    private val onResult: (BlinkFrameResult) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker

    private var frameIndex = 0

    // 实际产出帧率：滚动 1 秒窗口内 FaceLandmarker 真实完成的结果时间戳
    private val resultNanos = ArrayDeque<Long>()

    @Volatile
    private var pendingFrame: PendingFrame? = null

    private data class PendingFrame(
        val startNanos: Long,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    init {
        faceLandmarker = FaceLandmarker.createFromOptions(
            context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setDelegate(Delegate.GPU).setModelAssetPath(MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setResultListener { result, _ -> handleResult(result) }
                .build()
        )
    }

    override fun analyze(imageProxy: ImageProxy) {
        // 帧率限制：每 [DETECTION_FRAME_INTERVAL] 帧分析一次，其余直接关闭
        frameIndex += 1
        if (frameIndex % DETECTION_FRAME_INTERVAL != 0) {
            imageProxy.close()
            return
        }

        val startNanos = System.nanoTime()

        // 获取图片旋转角度，得到图片的真实宽高
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
            // rotationDegrees 不为 0 时，需要对图片应用旋转
            val upright = rotateBitmap(toArgbBitmap(imageProxy), rotationDegrees)
            val mpImage = BitmapImageBuilder(upright).build()

            pendingFrame = PendingFrame(
                startNanos = startNanos,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
            // CameraX 时间戳为纳秒，MediaPipe detectAsync 期望毫秒
            faceLandmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp / 1_000_000)
        } catch (throwable: Throwable) {
            pendingFrame = null
            onError(throwable)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val frame = pendingFrame ?: return
        pendingFrame = null
        val latencyMillis = (System.nanoTime() - frame.startNanos) / 1_000_000L
        val timestampMillis = System.currentTimeMillis()
        val resultFps = updateResultFps(System.nanoTime())

        val landmarks = result.faceLandmarks().firstOrNull()
        val face = landmarks?.let { lms ->
            val eyes = eyes(lms, frame.sourceWidth, frame.sourceHeight)
            BlinkFrameResult(
                faceBox = lms.boundingBox(frame.sourceWidth, frame.sourceHeight),
                leftEye = eyes.first,
                rightEye = eyes.second,
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                timestampMillis = timestampMillis,
                detectionLatencyMillis = latencyMillis,
                fps = resultFps
            )
        }

        onResult(
            face ?: BlinkFrameResult(
                faceBox = null,
                leftEye = null,
                rightEye = null,
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                timestampMillis = timestampMillis,
                detectionLatencyMillis = latencyMillis,
                fps = resultFps
            )
        )
    }

    private fun eyes(
        lms: List<NormalizedLandmark>,
        sourceWidth: Int,
        sourceHeight: Int
    ): Pair<BlinkEyeLandmarks?, BlinkEyeLandmarks?> {
        val left = extractEye(earIndices = LEFT_EAR, irisIndices = LEFT_IRIS, lms = lms, sourceWidth = sourceWidth, sourceHeight = sourceHeight)
        val right = extractEye(earIndices = RIGHT_EAR, irisIndices = RIGHT_IRIS, lms = lms, sourceWidth = sourceWidth, sourceHeight = sourceHeight)
        return left to right
    }

    private fun extractEye(
        earIndices: IntArray,
        irisIndices: IntArray,
        lms: List<NormalizedLandmark>,
        sourceWidth: Int,
        sourceHeight: Int
    ): BlinkEyeLandmarks? {
        val earPoints = earIndices
            .map { index -> lms.getOrNull(index)?.toPoint(sourceWidth, sourceHeight) }
            .filterNotNull()
        val irisPoints = irisIndices
            .map { index -> lms.getOrNull(index)?.toPoint(sourceWidth, sourceHeight) }
            .filterNotNull()
        if (earPoints.size < 6 || irisPoints.isEmpty()) return null

        return BlinkEyeLandmarks(
            earPoints = earPoints,
            irisPoints = irisPoints,
            irisDiameterPx = maxIrisDiameter(irisPoints)
        )
    }

    /** 虹膜直径 = 5 个虹膜点中任意两点的最大距离（对点序不敏感）。 */
    private fun maxIrisDiameter(points: List<Point2f>): Float {
        var max = 0f
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val d = hypot(points[i].x - points[j].x, points[i].y - points[j].y)
                if (d > max) max = d
            }
        }
        return max
    }

    private fun List<NormalizedLandmark>.boundingBox(
        sourceWidth: Int,
        sourceHeight: Int
    ): BoundingBox {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        forEach { landmark ->
            val x = landmark.x() * sourceWidth
            val y = landmark.y() * sourceHeight
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return BoundingBox(
            left = minX.coerceAtLeast(0f),
            top = minY.coerceAtLeast(0f),
            right = maxX.coerceAtMost(sourceWidth - 1f),
            bottom = maxY.coerceAtMost(sourceHeight - 1f)
        )
    }

    private fun NormalizedLandmark.toPoint(sourceWidth: Int, sourceHeight: Int): Point2f {
        return Point2f(
            x = x().coerceIn(0f, 1f) * sourceWidth,
            y = y().coerceIn(0f, 1f) * sourceHeight
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) {
            return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun toArgbBitmap(imageProxy: ImageProxy): Bitmap {
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

    /**
     * 实际产出帧率：统计过去 [FPS_WINDOW_NANOS] 窗口内真实完成的检测结果数。
     * 在 handleResult（MediaPipe 回调）侧测量——detectAsync 异步执行，
     * 相机送帧节奏 ≠ 推理产出节奏，而 UI 数据按结果刷新，帧率必须与之一致。
     */
    private fun updateResultFps(nowNanos: Long): Float {
        resultNanos.addLast(nowNanos)
        val windowStart = nowNanos - FPS_WINDOW_NANOS
        while (resultNanos.isNotEmpty() && resultNanos.first() < windowStart) {
            resultNanos.removeFirst()
        }
        return resultNanos.size.toFloat()
    }

    fun close() {
        faceLandmarker.close()
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val DETECTION_FRAME_INTERVAL = 1
        private const val FPS_WINDOW_NANOS = 1_000_000_000L

        // EAR 6 点（MediaPipe FaceMesh 478 点索引，p1..p6）
        private val LEFT_EAR = intArrayOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EAR = intArrayOf(33, 160, 158, 133, 153, 144)

        // 虹膜 5 点（中心 + 4 边界）
        private val LEFT_IRIS = intArrayOf(473, 474, 475, 476, 477)
        private val RIGHT_IRIS = intArrayOf(468, 469, 470, 471, 472)
    }
}
