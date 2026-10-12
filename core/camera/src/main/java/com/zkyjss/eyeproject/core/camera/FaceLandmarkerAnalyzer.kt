package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
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
import com.zkyjss.eyeproject.core.model.HeadPoseEstimate
import com.zkyjss.eyeproject.core.model.Point2f
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

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
    private val resultHandler: (FaceLandmarkerResult) -> Unit = { result ->
        runCatching { handleResult(result) }.onFailure(onError)
    }

    private var frameIndex = 0
    private var previousFrameNanos = 0L
    private var smoothedFps = 0f

    private val isEmulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    // Debug 模拟器：可改用电脑摄像头画面（见 DebugCameraFeed）
    private val hostFeed = DebugCameraFeed.supported(context)
    private var lastHostSequence = 0L
    private var lastInferenceNanos = 0L

    private val pendingFrames = LinkedHashMap<Long, PendingFrame>()

    private data class PendingFrame(
        val startNanos: Long,
        val analysisElapsedRealtimeMs: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val fps: Float,
        val imageBrightness: Float,
        val bitmap: Bitmap,
    )

    init {
        DebugCameraFeed.ensureStarted(context)
        faceLandmarker = SharedFaceLandmarker.get(context)
        handlers.add(resultHandler)
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
        // 同一时刻只保留一次异步推理，确保光学像素与关键点属于同一帧。
        val busy = synchronized(pendingFrames) {
            val latest = pendingFrames.values.firstOrNull()
            if (latest != null && System.nanoTime() - latest.startNanos < 1_000_000_000L) true
            else { pendingFrames.clear(); false }
        }
        if (busy) {
            imageProxy.close()
            return
        }

        // 开始分析前记录时刻
        val startNanos = System.nanoTime()

        // 模拟器在 CPU 上推理：限制最高推理频率，避免把电脑 CPU 跑满
        if (isEmulator && startNanos - lastInferenceNanos < EMULATOR_MIN_INTERVAL_NANOS) {
            imageProxy.close()
            return
        }
        // 电脑摄像头画面正在送入但还没有新帧：跳过，不对同一张画面重复推理
        val hostFrame = if (hostFeed) DebugCameraFeed.takeIfNewer(lastHostSequence) else null
        if (hostFeed && hostFrame == null && DebugCameraFeed.active()) {
            imageProxy.close()
            return
        }
        lastInferenceNanos = startNanos

        val fps = updateFps(startNanos)

        // 获取图片旋转角度。
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 分析图片得到数据
        try {
            // Windows 上模拟器 webcam0 会错误地只取原视频的半幅；Debug 时改用电脑端送来的完整帧。
            val upright = hostFrame?.let { (seq, jpeg) ->
                lastHostSequence = seq
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } ?: rotateBitmap(toArgbBitmap(imageProxy), rotationDegrees)
            val sourceWidth = upright.width
            val sourceHeight = upright.height
            val mpImage = BitmapImageBuilder(upright).build()

            val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
            synchronized(pendingFrames) {
                pendingFrames[timestampMs] = PendingFrame(
                startNanos = startNanos,
                analysisElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                fps = fps,
                imageBrightness = estimateBrightness(upright),
                bitmap = upright,
                )
            }
            // CameraX 时间戳为纳秒，MediaPipe detectAsync 期望毫秒
            faceLandmarker.detectAsync(mpImage, timestampMs)
        } catch (throwable: Throwable) {
            synchronized(pendingFrames) { pendingFrames.clear() }
            onError(throwable)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val frame = synchronized(pendingFrames) { pendingFrames.remove(result.timestampMs()) } ?: return
        try {
            val latencyMillis = (System.nanoTime() - frame.startNanos) / 1_000_000L
            val timestampMillis = System.currentTimeMillis()
            val landmarks = result.faceLandmarks().firstOrNull()
            val face = landmarks?.let { lms ->
                val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
                toFaceMetrics(lms, frame.sourceWidth, frame.sourceHeight, frame.bitmap, matrix)
            }
            onResult(
                FaceLandmarkFrame(
                    faces = listOfNotNull(face),
                    sourceWidth = frame.sourceWidth,
                    sourceHeight = frame.sourceHeight,
                    timestampMillis = timestampMillis,
                    detectionLatencyMillis = latencyMillis,
                    fps = frame.fps,
                    imageBrightness = frame.imageBrightness,
                    analysisElapsedRealtimeMs = frame.analysisElapsedRealtimeMs,
                )
            )
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun toFaceMetrics(
        lms: List<NormalizedLandmark>,
        sourceWidth: Int,
        sourceHeight: Int,
        bitmap: Bitmap,
        matrix: FloatArray?,
    ): FaceMetrics {
        val left = extractEye("LEFT", LEFT_EAR, LEFT_IRIS, lms, sourceWidth, sourceHeight)
        val right = extractEye("RIGHT", RIGHT_EAR, RIGHT_IRIS, lms, sourceWidth, sourceHeight)
        return FaceMetrics(
            faceBox = lms.boundingBox(sourceWidth, sourceHeight),
            leftEye = left,
            rightEye = right,
            leftOptical = EyeOpticalDetector.measure(bitmap, left),
            rightOptical = EyeOpticalDetector.measure(bitmap, right),
            headPose = matrix?.let(::headPose),
        )
    }

    /** 4×4 列主序矩阵的旋转部分；去掉统一尺度后得到相对欧拉角。 */
    private fun headPose(matrix: FloatArray): HeadPoseEstimate? {
        if (matrix.size != 16) return null
        val scale = sqrt(matrix[0] * matrix[0] + matrix[1] * matrix[1] + matrix[2] * matrix[2])
        if (scale <= 0f) return null
        val r00 = matrix[0] / scale; val r10 = matrix[1] / scale
        val r20 = matrix[2] / scale; val r21 = matrix[6] / scale; val r22 = matrix[10] / scale
        val yaw = asin((-r20).coerceIn(-1f, 1f))
        val pitch = atan2(r21, r22)
        val roll = atan2(r10, r00)
        val degrees = (180.0 / Math.PI).toFloat()
        return HeadPoseEstimate(yaw * degrees, pitch * degrees, roll * degrees)
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
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
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
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
    }

    /** 稀疏采样全帧亮度；不可冒充照度 lux。 */
    private fun estimateBrightness(bitmap: Bitmap): Float {
        var total = 0f
        var count = 0
        for (row in 0 until 12) {
            val y = ((row + 0.5f) * bitmap.height / 12f).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until 12) {
                val x = ((column + 0.5f) * bitmap.width / 12f).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                total += 0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)
                count++
            }
        }
        return total / count
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
        synchronized(pendingFrames) { pendingFrames.clear() }
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val DETECTION_FRAME_INTERVAL = 2
        /** 模拟器推理间隔下限：约 15 帧/秒，与真机实际分析帧率（30 帧隔帧取 1）一致，保证眨眼检测不漏帧。 */
        private const val EMULATOR_MIN_INTERVAL_NANOS = 66_000_000L

        private val handlers = CopyOnWriteArrayList<(FaceLandmarkerResult) -> Unit>()

        // object为kotlin单例
        private object SharedFaceLandmarker {
            @Volatile
            private var instance: FaceLandmarker? = null

            fun get(context: Context): FaceLandmarker {
                instance?.let { return it }
                return synchronized(this) {
                    // 模拟器的 OpenGL 推理图可能在首帧报 GL_INVALID_ENUM；
                    // CPU 路径仍运行同一份模型，真机继续使用 GPU。
                    val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_ASSET)
                    if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
                        baseOptions.setDelegate(Delegate.CPU)
                    } else {
                        baseOptions.setDelegate(Delegate.GPU)
                            .setDelegateOptions(BaseOptions.DelegateOptions.GpuOptions.builder().build())
                    }
                    instance ?: FaceLandmarker.createFromOptions(
                        context,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions.build())
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumFaces(1)
                            .setOutputFaceBlendshapes(false)
                            .setOutputFacialTransformationMatrixes(true)
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
