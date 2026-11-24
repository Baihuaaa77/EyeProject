package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 借用人脸管线的前摄画面。手部推理共享单例并在独立线程串行执行，忙时直接跳帧，最多持有一张副本。 */
class HandGestureAnalyzer(context: Context, private val onSample: (Int, Long) -> Unit) : AutoCloseable {
    private val delivery = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private val context = context.applicationContext
    @Volatile private var closed = false
    @Volatile private var unavailable = false
    private var consecutiveFailures = 0
    private var lastFrameMs = 0L

    init {
        // 进入测试阶段即提前预热模型，避免首题出现冷启动丢帧。
        inferenceExecutor.execute {
            if (!closed) runCatching { SharedHandLandmarker.get(this.context) }
        }
    }

    @Synchronized
    fun offer(bitmap: Bitmap, timeMs: Long) {
        if (closed || unavailable || timeMs - lastFrameMs < MIN_FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) return
        lastFrameMs = timeMs
        val factor = MAX_INFERENCE_EDGE / maxOf(bitmap.width, bitmap.height)
        val copy = try {
            if (factor < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: RuntimeException) {
            busy.set(false)
            return
        }
        if (copy == null) {
            busy.set(false)
            return
        }
        inferenceExecutor.execute {
            try {
                if (!closed) {
                    val direction = classify(copy, timeMs)
                    consecutiveFailures = 0
                    delivery.post { if (!closed) onSample(direction, timeMs) }
                }
            } catch (e: Exception) {
                consecutiveFailures += 1
                Log.w("HandGestureAnalyzer", "手部检测异常（第 $consecutiveFailures 次）", e)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    unavailable = true
                }
            } finally {
                copy.recycle()
                busy.set(false)
            }
        }
    }

    private fun classify(bitmap: Bitmap, timeMs: Long): Int {
        val hands = SharedHandLandmarker.detect(context, bitmap, timeMs)
        val longest = maxOf(bitmap.width, bitmap.height).toFloat()
        val xScale = bitmap.width / longest
        val yScale = bitmap.height / longest
        val directions = IntArray(hands.size) { handIndex ->
            val hand = hands[handIndex]
            val points = FloatArray(handJoints.size * 2)
            handJoints.forEachIndexed { index, joint ->
                // 输入是未镜像前摄图，翻转横轴后才是使用者自己的左右。
                points[index * 2] = (1f - hand[joint].x()) * xScale
                points[index * 2 + 1] = hand[joint].y() * yScale
            }
            HandPointing.direction(points)
        }
        return HandPointing.selectDirection(directions)
    }

    @Synchronized
    override fun close() {
        closed = true
    }

    /** 进程内共享 HandLandmarker：避免每次换眼/暂停反复重建，且 GPU 失败时自动回退 CPU。 */
    private object SharedHandLandmarker {
        private var landmarker: HandLandmarker? = null
        private var usingGpu = false
        private var lastVideoTimestampMs = 0L

        fun get(context: Context): HandLandmarker {
            landmarker?.let { return it }
            val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
            val created = if (emulator) {
                usingGpu = false
                create(context, gpu = false)
            } else {
                runCatching { create(context, gpu = true).also { usingGpu = true } }
                    .getOrElse {
                        Log.w("HandGestureAnalyzer", "GPU 初始化失败，回退 CPU", it)
                        usingGpu = false
                        create(context, gpu = false)
                    }
            }
            landmarker = created
            return created
        }

        fun detect(context: Context, bitmap: Bitmap, timeMs: Long) =
            try {
                detectOnce(get(context), bitmap, timeMs)
            } catch (e: Exception) {
                if (!usingGpu) throw e
                // 个别机型 GPU 推理会在运行期失败：切换 CPU 重建并立即重试当前帧。
                Log.w("HandGestureAnalyzer", "GPU 推理失败，回退 CPU", e)
                runCatching { landmarker?.close() }
                landmarker = null
                usingGpu = false
                lastVideoTimestampMs = 0L
                val cpu = create(context, gpu = false).also { landmarker = it }
                detectOnce(cpu, bitmap, timeMs)
            }

        private fun detectOnce(detector: HandLandmarker, bitmap: Bitmap, timeMs: Long) =
            BitmapImageBuilder(bitmap).build().let { image ->
                try {
                    // VIDEO 模式要求时间戳严格递增，跨会话复用时用单调时钟守卫。
                    val videoTimestampMs = maxOf(timeMs, lastVideoTimestampMs + 1L)
                    lastVideoTimestampMs = videoTimestampMs
                    detector.detectForVideo(image, videoTimestampMs).landmarks()
                } finally {
                    image.close()
                }
            }

        private fun create(context: Context, gpu: Boolean): HandLandmarker {
            val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_ASSET)
            if (gpu) {
                baseOptions.setDelegate(Delegate.GPU)
                    .setDelegateOptions(BaseOptions.DelegateOptions.GpuOptions.builder().build())
            } else {
                baseOptions.setDelegate(Delegate.CPU)
            }
            return HandLandmarker.createFromOptions(
                context,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions.build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.35f)
                    .setMinHandPresenceConfidence(0.35f)
                    .setMinTrackingConfidence(0.35f)
                    .build(),
            )
        }
    }

    private companion object {
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val MIN_FRAME_INTERVAL_MS = 50L
        private const val MAX_INFERENCE_EDGE = 480f
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private val inferenceExecutor = Executors.newSingleThreadExecutor()
        // 17 点：手腕(0) + 大拇指(1..4) + 食/中/无名/小指 MCP/PIP/TIP
        val handJoints = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 13, 14, 16, 17, 18, 20)
    }
}
