package com.zkyjss.eyeproject.feature.testmodel.gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.FaceEyeDetection
import com.zkyjss.eyeproject.core.model.FrameResult
import androidx.core.graphics.createBitmap

/**
 * 纯人脸检测器（只输出人脸框），基于 MediaPipe Face Detector。
 * 比 FaceLandmarker 更轻量，适合只需要检测框的场景。
 */
class FaceDetectorAnalyzer(
    context: Context,
    private val onResult: (FrameResult) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceDetector: FaceDetector
    private var frameIndex = 0
    private var previousFrameNanos = 0L
    private var smoothedFps = 0f

    init {
        try {
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.5f)
                .build()
            faceDetector = FaceDetector.createFromOptions(context, options)
        } catch (t: Throwable) {
            throw t
        }
    }

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
            val upright = rotateBitmap(toArgbBitmap(imageProxy), rotationDegrees)
            val mpImage = BitmapImageBuilder(upright).build()

            val result = faceDetector.detect(mpImage)
            val latencyMillis = (System.nanoTime() - startNanos) / 1_000_000L
            val timestampMillis = System.currentTimeMillis()

            // 将 FaceDetector 的检测结果转换为 core.model.FaceEyeDetection
            val faceEyeDetections = result.detections().map { detection ->
                val box = detection.boundingBox()
                FaceEyeDetection(
                    faceId = 0,
                    faceBox = BoundingBox(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom
                    ),
                    leftEye = null,   // FaceDetector 不输出眼部信息
                    rightEye = null,  // FaceDetector 不输出眼部信息
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    timestampMillis = timestampMillis,
                    detectionLatencyMillis = latencyMillis,
                    fps = fps
                )
            }

            onResult(
                FrameResult(
                    detections = faceEyeDetections,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    timestampMillis = timestampMillis,
                    detectionLatencyMillis = latencyMillis,
                    fps = fps
                )
            )

        } catch (t: Throwable) {
            onError(t)
        } finally {
            imageProxy.close()
        }
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
        val bitmap = createBitmap(bitmapWidth, imageProxy.height)
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
            // 0.5/0.5：快速反映真实帧率（原 0.85/0.15 滞后太严重，推理变慢时 fps 几十秒才降下来）
            0.5f * smoothedFps + 0.5f * frameFps
        }
        return smoothedFps
    }

    fun close() {
        faceDetector.close()
    }

    companion object {
        private const val MODEL_ASSET = "blaze_face_short_range.tflite"
        private const val DETECTION_FRAME_INTERVAL = 2
    }
}