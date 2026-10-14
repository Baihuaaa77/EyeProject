package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.math.hypot

/**
 * 基于 EAR（Eye Aspect Ratio）的眨眼检测与眨眼次数统计。
 *
 * 方法（用户给定）：
 * - 每只眼取 6 个关键点，EAR = (||p2-p6|| + ||p3-p5||) / (2 × ||p1-p4||)
 * - EAR 阈值 0.23，连续 3 帧低于阈值 → 判为闭眼；回升到阈值以上 → 记录一次完整眨眼
 * - 累计眨眼次数 [blinkCount]，调用 [reset] 清零（每次采样后重置，得到区间眨眼次数）
 */
class BlinkDetector(
    private val earThreshold: Float = 0.23f,
    private val consecutiveFrames: Int = 3
) {
    // update() 跑在相机分析线程，blinkCount / reset() 由协程（每秒展示、每 30 秒采样）读取与清零，
    // 跨线程访问，必须互斥——否则 reset() 撞上 update() 会丢眨眼或把计数清成 0。
    private val lock = DomainLock()
    private var closedFrameCount = 0
    private var isClosed = false
    private var _blinkCount = 0

    /** 累计眨眼次数（自上次 [reset] 起）。 */
    val blinkCount: Int
        get() = lock.withLock { _blinkCount }

    /** 每次完成一次完整眨眼时回调（用于记录连续睁眼时长）；在调用 [update] 的线程上执行。 */
    var onBlink: (() -> Unit)? = null

    /** 每帧调用，传入双眼平均 EAR；未检测到眼睛时传 null（不中断计数）。 */
    fun update(ear: Float?) {
        if (ear == null) return
        var completedBlink = false
        lock.withLock {
            if (ear < earThreshold) {
                closedFrameCount++
                if (closedFrameCount >= consecutiveFrames) {
                    isClosed = true
                }
            } else {
                if (isClosed) {
                    _blinkCount++
                    isClosed = false
                    completedBlink = true
                }
                closedFrameCount = 0
            }
        }
        // 回调放在锁外：onBlink 会写调用方的 @Volatile 字段，不该在持锁时执行
        if (completedBlink) onBlink?.invoke()
    }

    /** 跟踪中断（如人脸短暂丢失）：只清闭眼状态，保留本区间已累计的眨眼次数。 */
    fun interrupt() {
        lock.withLock {
            closedFrameCount = 0
            isClosed = false
        }
    }

    fun reset() {
        lock.withLock {
            closedFrameCount = 0
            isClosed = false
            _blinkCount = 0
        }
    }

    companion object {
        /** 双眼平均 EAR；双眼都不可用时返回 null。 */
        fun averageEar(left: EyeLandmarks?, right: EyeLandmarks?): Float? {
            val leftEar = left?.let { ear(it.earPoints) }
            val rightEar = right?.let { ear(it.earPoints) }
            return when {
                leftEar != null && rightEar != null -> (leftEar + rightEar) / 2f
                leftEar != null -> leftEar
                rightEar != null -> rightEar
                else -> null
            }
        }

        /** EAR 公式：6 点 p1..p6。 */
        fun ear(points: List<Point2f>): Float? {
            if (points.size < 6) return null
            val p1 = points[0]
            val p2 = points[1]
            val p3 = points[2]
            val p4 = points[3]
            val p5 = points[4]
            val p6 = points[5]
            val vertical =
                hypot(p2.x - p6.x, p2.y - p6.y) + hypot(p3.x - p5.x, p3.y - p5.y)
            val horizontal = 2f * hypot(p1.x - p4.x, p1.y - p4.y)
            if (horizontal <= 0f) return null
            return vertical / horizontal
        }
    }
}
