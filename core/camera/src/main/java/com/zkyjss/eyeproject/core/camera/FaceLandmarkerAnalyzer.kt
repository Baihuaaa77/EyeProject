package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
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
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.Point2f
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

/**
 * 基于 MediaPipe FaceLandmarker（478 点）的逐帧分析器，输出疲劳检测所需指标：
 * 人脸框、双眼 EAR 6 点、虹膜 5 点与虹膜直径。
 */
class FaceLandmarkerAnalyzer(
    context: Context,
    private val onResult: (FaceLandmarkFrame) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker
    private val resultHandler: (FaceLandmarkerResult) -> Unit = { handleResult(it) }

    private var frameIndex = 0
    private var previousFrameNanos = 0L
    private var smoothedFps = 0f

    @Volatile
    private var pendingFrame: PendingFrame? = null

    private data class PendingFrame(
        val startNanos: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val fps: Float
    )

    init {
        handlers.add(resultHandler)
        faceLandmarker = SharedFaceLandmarker.get(context)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // 帧率限制，相机不断传来图片，每DETECTION_FRAME_INTERVAL张分析一次
        // 否则关闭imageProxy，然后返回
        frameIndex += 1
        if (frameIndex % DETECTION_FRAME_INTERVAL != 0) {
            imageProxy.close()
            return
        }

        // 开始分析前记录时刻
        val startNanos = System.nanoTime()

        val fps = updateFps(startNanos)

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

        // 分析图片得到数据
        try {
            // rotationDegrees不为0时，需要对图片应用旋转
            val upright = rotateBitmap(toArgbBitmap(imageProxy), rotationDegrees)
            val mpImage = BitmapImageBuilder(upright).build()

            pendingFrame = PendingFrame(
                startNanos = startNanos,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                fps = fps
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

        val landmarks = result.faceLandmarks().firstOrNull()

        val face = landmarks?.let { lms ->
            toFaceMetrics(lms, frame.sourceWidth, frame.sourceHeight)
        }

        onResult(
            FaceLandmarkFrame(
                faces = listOfNotNull(face),
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                timestampMillis = timestampMillis,
                detectionLatencyMillis = latencyMillis,
                fps = frame.fps
            )
        )
    }

    private fun toFaceMetrics(
        lms: List<NormalizedLandmark>,
        sourceWidth: Int,
        sourceHeight: Int
    ): FaceMetrics {
        return FaceMetrics(
            faceBox = lms.boundingBox(sourceWidth, sourceHeight),
            leftEye = extractEye(
                label = "LEFT",
                earIndices = LEFT_EAR,
                irisIndices = LEFT_IRIS,
                lms = lms,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            ),
            rightEye = extractEye(
                label = "RIGHT",
                earIndices = RIGHT_EAR,
                irisIndices = RIGHT_IRIS,
                lms = lms,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
        )
    }

    private fun extractEye(
        label: String,
        earIndices: IntArray,
        irisIndices: IntArray,
        lms: List<NormalizedLandmark>,
        sourceWidth: Int,
        sourceHeight: Int
    ): EyeLandmarks? {
        val earPoints = earIndices
            .map { index -> lms.getOrNull(index)?.toPoint(sourceWidth, sourceHeight) }
            .filterNotNull()
        val irisPoints = irisIndices
            .map { index -> lms.getOrNull(index)?.toPoint(sourceWidth, sourceHeight) }
            .filterNotNull()
        if (earPoints.size < 6 || irisPoints.isEmpty()) return null

        return EyeLandmarks(
            label = label,
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

    private fun updateFps(nowNanos: Long): Float {
        if (previousFrameNanos == 0L) {
            previousFrameNanos = nowNanos
            return smoothedFps
        }
        val frameFps = 1_000_000_000f / (nowNanos - previousFrameNanos).coerceAtLeast(1L)
        previousFrameNanos = nowNanos
        smoothedFps = if (smoothedFps == 0f) {
            frameFps
        } else {
            0.5f * smoothedFps + 0.5f * frameFps
        }
        return smoothedFps
    }

    fun close() {
        handlers.remove(resultHandler)
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val DETECTION_FRAME_INTERVAL = 1

        private val handlers = CopyOnWriteArrayList<(FaceLandmarkerResult) -> Unit>()

        // object为kotlin单例
        private object SharedFaceLandmarker {
            @Volatile
            private var instance: FaceLandmarker? = null

            fun get(context: Context): FaceLandmarker {
                instance?.let { return it }
                return synchronized(this) {
                    instance ?: FaceLandmarker.createFromOptions(
                        context,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(
                                BaseOptions.builder().setDelegate(Delegate.GPU).setDelegateOptions(BaseOptions.DelegateOptions.GpuOptions.builder().build()).setModelAssetPath(MODEL_ASSET).build()
                            )
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumFaces(1)
                            .setOutputFaceBlendshapes(false)
                            .setResultListener { result, _ ->
                                handlers.forEach { it(result) }
                            }
                            .build()
                    ).also { instance = it }
                }
            }
        }

        // EAR 6 点（MediaPipe FaceMesh 478 点索引，p1..p6）
        private val LEFT_EAR = intArrayOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EAR = intArrayOf(33, 160, 158, 133, 153, 144)

        // 虹膜 5 点（中心 + 4 边界）
        private val LEFT_IRIS = intArrayOf(473, 474, 475, 476, 477)
        private val RIGHT_IRIS = intArrayOf(468, 469, 470, 471, 472)
    }
}
