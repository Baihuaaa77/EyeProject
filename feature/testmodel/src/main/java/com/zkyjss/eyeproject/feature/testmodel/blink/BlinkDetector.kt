package com.zkyjss.eyeproject.feature.testmodel.blink

import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.math.hypot

/**
 * 基于 EAR（Eye Aspect Ratio）的眨眼检测与眨眼次数统计。
 * 本模块独立实现（实验页不依赖 core:domain 算法），与 core:domain 的 BlinkDetector 算法保持一致。
 *
 * 方法：
 * - 每只眼取 6 个关键点，EAR = (||p2-p6|| + ||p3-p5||) / (2 × ||p1-p4||)
 * - EAR 阈值 0.23，连续 3 帧低于阈值 → 判为闭眼；回升到阈值以上 → 记录一次完整眨眼
 * - 累计眨眼次数 [blinkCount]，调用 [reset] 清零（开始新一段观察）
 */
class BlinkDetector(
    private val earThreshold: Float = 0.23f,
    private val consecutiveFrames: Int = 3
) {
    private var closedFrameCount = 0
    private var isClosed = false

    /** 累计眨眼次数（自上次 [reset] 起）。 */
    var blinkCount = 0
        private set

    /** 当前是否判为闭眼（连续 [consecutiveFrames] 帧低于阈值）。 */
    val isEyeClosed: Boolean
        get() = isClosed

    /** 每帧调用，传入双眼平均 EAR；未检测到眼睛时传 null（不中断计数）。 */
    fun update(ear: Float?) {
        if (ear == null) return
        if (ear < earThreshold) {
            closedFrameCount++
            if (closedFrameCount >= consecutiveFrames) {
                isClosed = true
            }
        } else {
            if (isClosed) {
                blinkCount++
                isClosed = false
            }
            closedFrameCount = 0
        }
    }

    fun reset() {
        closedFrameCount = 0
        isClosed = false
        blinkCount = 0
    }

    companion object {
        /** 双眼平均 EAR；双眼都不可用时返回 null。 */
        fun averageEar(left: BlinkEyeLandmarks?, right: BlinkEyeLandmarks?): Float? {
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
