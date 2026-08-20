package com.zkyjss.eyeproject.feature.camera

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
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeDetection
import com.zkyjss.eyeproject.core.model.FaceEyeDetection
import com.zkyjss.eyeproject.core.model.FrameResult
import com.zkyjss.eyeproject.core.model.Point2f

/**
 * 基于 MediaPipe Tasks（tasks-vision FaceLandmarker）的逐帧分析器。
 *
 * FaceLandmarker 输出 478 点（468 脸点 + 10 虹膜点），与底层 iris_tracking 图的结构一致。
 * LIVE_STREAM 异步回调，Tasks 内部处理 backpressure。
 */
class FaceLandmarkerAnalyzer(
    context: Context,
    private val onResult: (FrameResult) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker

    private var frameIndex = 0
    private var previousFrameNanos = 0L
    private var smoothedFps = 0f

    @Volatile
    private var pendingFrame: PendingFrame? = null

    private data class PendingFrame(
        val startNanos: Long,
        val rotationDegrees: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val fps: Float
    )

    init {
        try {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setResultListener { result, _ -> handleResult(result) }
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (t: Throwable) {
            throw t
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        frameIndex += 1
        if (frameIndex % DETECTION_FRAME_INTERVAL != 0) {
            imageProxy.close()
            return
        }

        val startNanos = System.nanoTime()
        val fps = updateFps(startNanos)
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
            // 拷贝成 Bitmap（BitmapImageBuilder 有拷贝），imageProxy 可立即 close
            val upright = rotateBitmap(toArgbBitmap(imageProxy), rotationDegrees)
            val mpImage = BitmapImageBuilder(upright).build()

            pendingFrame = PendingFrame(
                startNanos = startNanos,
                rotationDegrees = rotationDegrees,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                fps = fps
            )
            faceLandmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
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
        val detection = landmarks?.toFaceEyeDetection(
            faceId = 0,
            sourceWidth = frame.sourceWidth,
            sourceHeight = frame.sourceHeight,
            rotationDegrees = frame.rotationDegrees,
            timestampMillis = timestampMillis,
            detectionLatencyMillis = latencyMillis,
            fps = frame.fps
        )

        onResult(
            FrameResult(
                detections = listOfNotNull(detection),
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                timestampMillis = timestampMillis,
                detectionLatencyMillis = latencyMillis,
                fps = frame.fps
            )
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

    private fun List<NormalizedLandmark>.toFaceEyeDetection(
        faceId: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        timestampMillis: Long,
        detectionLatencyMillis: Long,
        fps: Float
    ): FaceEyeDetection {
        return FaceEyeDetection(
            faceId = faceId,
            faceBox = boundingBox(sourceWidth, sourceHeight),
            leftEye = extractEye(
                label = "LEFT",
                outlineIndices = LEFT_EYE_OUTLINE,
                outerCorner = LEFT_EYE_OUTER,
                innerCorner = LEFT_EYE_INNER,
                iris = LEFT_IRIS,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            ),
            rightEye = extractEye(
                label = "RIGHT",
                outlineIndices = RIGHT_EYE_OUTLINE,
                outerCorner = RIGHT_EYE_OUTER,
                innerCorner = RIGHT_EYE_INNER,
                iris = RIGHT_IRIS,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            ),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            timestampMillis = timestampMillis,
            detectionLatencyMillis = detectionLatencyMillis,
            fps = fps
        )
    }

    private fun List<NormalizedLandmark>.extractEye(
        label: String,
        outlineIndices: IntArray,
        outerCorner: Int,
        innerCorner: Int,
        iris: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): EyeDetection? {
        val outlinePoints = outlineIndices
            .map { index -> getOrNull(index) }
            .filterNotNull()
            .map { it.toPoint(sourceWidth, sourceHeight) }
        val outer = getOrNull(outerCorner) ?: return null
        val inner = getOrNull(innerCorner) ?: return null
        val irisPoint = getOrNull(iris)?.toPoint(sourceWidth, sourceHeight)

        val center = Point2f(
            x = (outer.x() + inner.x()) / 2f * sourceWidth,
            y = (outer.y() + inner.y()) / 2f * sourceHeight
        )
        return EyeDetection(
            label = label,
            points = outlinePoints,
            center = center,
            irisCenter = irisPoint
        )
    }

    private fun List<NormalizedLandmark>.boundingBox(sourceWidth: Int, sourceHeight: Int): BoundingBox {
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
            0.85f * smoothedFps + 0.15f * frameFps
        }
        return smoothedFps
    }

    fun close() {
        faceLandmarker.close()
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val DETECTION_FRAME_INTERVAL = 4

        // 眼部索引沿用 Face Landmarker 的 478 点约定（MediaPipe 左/右为被摄者自身）。
        private val RIGHT_EYE_OUTLINE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133)
        private val LEFT_EYE_OUTLINE = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263)
        private const val RIGHT_EYE_OUTER = 33
        private const val RIGHT_EYE_INNER = 133
        private const val LEFT_EYE_OUTER = 362
        private const val LEFT_EYE_INNER = 263
        private const val RIGHT_IRIS = 468
        private const val LEFT_IRIS = 473
    }
}
