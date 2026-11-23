package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 借用人脸管线的前摄画面。手部推理独立执行，忙时直接跳帧，最多持有一张副本。 */
class HandGestureAnalyzer(context: Context, private val onSample: (Int, Long) -> Unit) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val delivery = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private val context = context.applicationContext
    @Volatile private var closed = false
    @Volatile private var unavailable = false
    private var lastFrameMs = 0L
    private var landmarker: HandLandmarker? = null

    @Synchronized
    fun offer(bitmap: Bitmap, timeMs: Long) {
        if (closed || unavailable || timeMs - lastFrameMs < 75L || !busy.compareAndSet(false, true)) return
        lastFrameMs = timeMs
        val factor = 640f / maxOf(bitmap.width, bitmap.height)
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
        executor.execute {
            try {
                if (!closed) {
                    val direction = classify(copy, timeMs)
                    delivery.post { if (!closed) onSample(direction, timeMs) }
                }
            } catch (e: Exception) {
                unavailable = true
                Log.w("HandGestureAnalyzer", "手部检测不可用", e)
            } finally {
                copy.recycle()
                busy.set(false)
            }
        }
    }

    private fun classify(bitmap: Bitmap, timeMs: Long): Int {
        val detector = landmarker ?: HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .build(),
        ).also { landmarker = it }
        val image = BitmapImageBuilder(bitmap).build()
        val hands = try { detector.detectForVideo(image, timeMs).landmarks() } finally { image.close() }
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
        if (closed) return
        closed = true
        executor.execute { landmarker?.close() }
        executor.shutdown()
    }

    private companion object {
        val handJoints = intArrayOf(0, 5, 6, 8, 9, 10, 12, 13, 14, 16, 17, 18, 20)
    }
}
