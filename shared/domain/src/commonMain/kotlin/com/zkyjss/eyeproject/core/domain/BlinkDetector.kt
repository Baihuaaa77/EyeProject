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
 *
 * 计时模式（[minClosedMs] 非空，供三病种测试使用）：「连续 3 帧」在 10 fps 时要求闭眼 ≥ 300 ms，
 * 会漏掉多数正常眨眼；改为按时间判定——从首个低于阈值的帧到首个回升帧的时长 ≥ [minClosedMs] 即为一次眨眼，
 * 与帧率无关。[adaptive] 时阈值取近期 EAR 中位数（睁眼基线）的 [adaptiveRatio]，适应眼裂大小与戴镜差异。
 */
class BlinkDetector(
    private val earThreshold: Float = 0.23f,
    private val consecutiveFrames: Int = 3,
    private val minClosedMs: Long? = null,
    private val adaptive: Boolean = false,
    private val adaptiveRatio: Float = 0.72f,
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

    private var closedSinceMs = -1L
    private val recentEar = ArrayDeque<Float>()

    /** 当前生效的闭眼阈值（自适应模式下随睁眼基线变化）。 */
    val threshold: Float
        get() = lock.withLock { currentThreshold() }

    private fun currentThreshold(): Float {
        if (!adaptive || recentEar.size < ADAPTIVE_MIN_SAMPLES) return earThreshold
        val median = recentEar.sorted()[recentEar.size / 2]
        return (median * adaptiveRatio).coerceIn(ADAPTIVE_MIN, ADAPTIVE_MAX)
    }

    /**
     * 每帧调用，传入双眼平均 EAR；未检测到眼睛时传 null（不中断计数）。
     * 计时模式需传入帧时间 [timeMs]（毫秒，单调递增）；缺省时退回按帧数判定。
     */
    fun update(ear: Float?, timeMs: Long? = null) {
        if (ear == null) return
        var completedBlink = false
        lock.withLock {
            val threshold = currentThreshold()
            if (adaptive) {
                recentEar.addLast(ear)
                if (recentEar.size > ADAPTIVE_WINDOW) recentEar.removeFirst()
                // 睁眼基线建立前不判定，避免用固定阈值把眼裂小的人整段判成闭眼
                if (recentEar.size < ADAPTIVE_MIN_SAMPLES) return@withLock
            }
            val timed = minClosedMs != null && timeMs != null
            if (ear < threshold) {
                closedFrameCount++
                if (timed && closedSinceMs < 0) closedSinceMs = timeMs!!
                if (!timed && closedFrameCount >= consecutiveFrames) {
                    isClosed = true
                }
            } else {
                if (timed && closedSinceMs >= 0 && timeMs!! - closedSinceMs >= minClosedMs!!) isClosed = true
                if (isClosed) {
                    _blinkCount++
                    isClosed = false
                    completedBlink = true
                }
                closedFrameCount = 0
                closedSinceMs = -1L
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
            closedSinceMs = -1L
        }
    }

    fun reset() {
        lock.withLock {
            closedFrameCount = 0
            isClosed = false
            closedSinceMs = -1L
            _blinkCount = 0
        }
    }

    companion object {
        /** 自适应阈值：约 2 秒（30 fps）的滑动窗口，至少 15 帧后启用，阈值限制在 0.14–0.26。 */
        private const val ADAPTIVE_WINDOW = 60
        private const val ADAPTIVE_MIN_SAMPLES = 15
        private const val ADAPTIVE_MIN = 0.14f
        private const val ADAPTIVE_MAX = 0.26f

        /** 计时眨眼的最短闭眼时长：30 fps 需 2 帧、10 fps 1 帧即可，单帧关键点抖动不计。 */
        const val DRY_EYE_MIN_CLOSED_MS = 60L

        /** 干眼测试的 EAR 判据：按时间判定 + 个人自适应阈值。正式测试与首页实时视窗（[LiveEyeMonitor]）共用这一份配置。 */
        fun dryEyeEar(): BlinkDetector = BlinkDetector(minClosedMs = DRY_EYE_MIN_CLOSED_MS, adaptive = true)

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
