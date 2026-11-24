package com.zkyjss.eyeproject.core.camera

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 从使用者视角判断单手指向方向（0=右、1=下、2=左、3=上）。
 * 坐标以图像长边归一化，x 向右、y 向下。
 *
 * 同时支持两种更易上手的手势：
 * 1. 食指指向：允许其余三指自然半弯，但拒绝张开手掌（防止遮眼手误触）。
 * 2. 握拳竖大拇指：其余四指收拢时，大拇指朝上/下/左/右指向（向下与横向比食指更省力）。
 */
object HandPointing {
    const val NEUTRAL = -1
    const val AMBIGUOUS = -2

    private const val MIN_PALM_SCALE = 0.018f
    /** 方向主轴占优比：约允许偏离水平/垂直 ±37°，仅在 45° 对角线附近 ±8° 保留模糊区。 */
    private const val AXIS_DOMINANCE = 1.32f

    /** 遮盖单眼的手可能也在画面中。仅在无冲突地指向同一方向时接受。 */
    fun selectDirection(hands: IntArray): Int {
        var chosen = NEUTRAL
        var ambiguous = false
        for (hand in hands) {
            when {
                hand in 0..3 -> {
                    if (chosen == NEUTRAL) chosen = hand
                    else if (chosen != hand) return AMBIGUOUS
                }
                hand == AMBIGUOUS -> ambiguous = true
            }
        }
        return when {
            chosen in 0..3 -> chosen
            ambiguous && hands.size == 1 -> AMBIGUOUS
            else -> NEUTRAL
        }
    }

    /**
     * 判定单只手的指向。
     * 支持两种点集格式：
     * - 17 点（34 float，推荐）：手腕(0)，大拇指 CMC/MCP/IP/TIP(1..4)，食/中/无名/小指各自的 MCP/PIP/TIP(5..16)。
     * - 13 点（26 float，兼容）：手腕(0)，食/中/无名/小指各自的 MCP/PIP/TIP(1..12)。
     * 允许被掌心遮挡的蜷缩指尖为 NaN，不因单点置信度低误杀整只手。
     */
    fun direction(points: FloatArray): Int {
        val hasThumb = when (points.size) {
            34 -> true
            26 -> false
            else -> return AMBIGUOUS
        }
        val fingerOffset = if (hasThumb) 5 else 1

        fun valid(index: Int): Boolean = points[2 * index].isFinite() && points[2 * index + 1].isFinite()
        fun dist(a: Int, b: Int): Float =
            hypot(points[2 * a] - points[2 * b], points[2 * a + 1] - points[2 * b + 1])

        val wrist = 0
        val indexMcp = fingerOffset
        val middleMcp = fingerOffset + 3
        val pinkyMcp = fingerOffset + 9
        if (!valid(wrist)) return NEUTRAL

        // 综合手腕到中指根、食指根及掌宽作为参考尺度，降低手倾斜透视缩短的影响。
        var palm = 0f
        if (valid(middleMcp)) palm = maxOf(palm, dist(wrist, middleMcp))
        if (valid(indexMcp)) palm = maxOf(palm, dist(wrist, indexMcp))
        if (valid(indexMcp) && valid(pinkyMcp)) palm = maxOf(palm, dist(indexMcp, pinkyMcp) * 1.15f)
        if (palm < MIN_PALM_SCALE) return NEUTRAL

        /** 判断某根手指（mcp/pip/tip）是否明显伸展。缺点的蜷缩手指视为未伸展。 */
        fun isFingerClearlyExtended(mcp: Int, referenceSpan: Float): Boolean {
            val pip = mcp + 1
            val tip = mcp + 2
            if (!valid(mcp) || !valid(pip) || !valid(tip)) return false
            val seg1 = dist(mcp, pip)
            val seg2 = dist(pip, tip)
            val len = seg1 + seg2
            if (len < 0.35f * palm) return false
            val span = dist(mcp, tip)
            val straight = span >= 0.78f * len
            val extendsBeyondPip = dist(wrist, tip) > dist(wrist, pip) + 0.16f * palm
            val comparableReach = referenceSpan <= 0f || span >= 0.62f * referenceSpan
            return straight && extendsBeyondPip && comparableReach
        }

        // 1) 优先检测食指指向
        val indexPip = indexMcp + 1
        val indexTip = indexMcp + 2
        if (valid(indexMcp) && valid(indexPip) && valid(indexTip)) {
            val indexLen = dist(indexMcp, indexPip) + dist(indexPip, indexTip)
            val indexSpan = dist(indexMcp, indexTip)
            val tipFromWrist = dist(wrist, indexTip)
            val pipFromWrist = dist(wrist, indexPip)
            val indexExtended = indexLen >= 0.42f * palm &&
                indexSpan >= 0.76f * indexLen &&
                indexSpan >= 0.35f * palm &&
                tipFromWrist >= 0.85f * palm &&
                tipFromWrist >= pipFromWrist - 0.05f * palm

            if (indexExtended) {
                // 其余三指中只要有一根明显伸直且与食指接近等长，视为非单指（如张开手掌遮眼），避免误触发。
                val otherExtended = isFingerClearlyExtended(fingerOffset + 3, indexSpan) ||
                    isFingerClearlyExtended(fingerOffset + 6, indexSpan) ||
                    isFingerClearlyExtended(fingerOffset + 9, indexSpan)
                if (!otherExtended) {
                    val dx = (points[2 * indexTip] - points[2 * indexMcp]) +
                        0.6f * (points[2 * indexTip] - points[2 * indexPip])
                    val dy = (points[2 * indexTip + 1] - points[2 * indexMcp + 1]) +
                        0.6f * (points[2 * indexTip + 1] - points[2 * indexPip + 1])
                    return classifyAxis(dx, dy)
                }
            }
        }

        // 2) 其次检测握拳竖大拇指指向（仅 17 点输入可用）
        if (hasThumb && valid(1) && valid(2) && valid(3) && valid(4)) {
            val thumbCmc = 1
            val thumbMcp = 2
            val thumbIp = 3
            val thumbTip = 4
            val thumbLen = dist(thumbMcp, thumbIp) + dist(thumbIp, thumbTip)
            val thumbSpan = dist(thumbMcp, thumbTip)
            val thumbStraight = thumbLen >= 0.30f * palm &&
                thumbSpan >= 0.76f * thumbLen &&
                thumbSpan >= 0.28f * palm &&
                dist(thumbCmc, thumbTip) > dist(thumbCmc, thumbIp) + 0.10f * palm

            if (thumbStraight) {
                // 其余四指均需处于收拢状态，防止张开手掌（遮眼）触发大拇指指向。
                val anyFingerExtended = (0..3).any { i ->
                    isFingerClearlyExtended(fingerOffset + i * 3, 0f)
                }
                if (!anyFingerExtended) {
                    // 大拇指尖需明显离开食指与中指掌指区域，排除自然握拳贴指状态。
                    var clearance = Float.MAX_VALUE
                    if (valid(indexMcp)) clearance = minOf(clearance, dist(thumbTip, indexMcp))
                    if (valid(indexPip)) clearance = minOf(clearance, dist(thumbTip, indexPip))
                    if (valid(middleMcp)) clearance = minOf(clearance, dist(thumbTip, middleMcp))
                    val awayFromPalm = !valid(middleMcp) ||
                        dist(thumbTip, middleMcp) > dist(thumbMcp, middleMcp) + 0.12f * palm
                    if (clearance >= 0.38f * palm && awayFromPalm) {
                        val dx = (points[2 * thumbTip] - points[2 * thumbMcp]) +
                            0.6f * (points[2 * thumbTip] - points[2 * thumbIp])
                        val dy = (points[2 * thumbTip + 1] - points[2 * thumbMcp + 1]) +
                            0.6f * (points[2 * thumbTip + 1] - points[2 * thumbIp + 1])
                        return classifyAxis(dx, dy)
                    }
                }
            }
        }

        return NEUTRAL
    }

    private fun classifyAxis(dx: Float, dy: Float): Int = when {
        abs(dx) > AXIS_DOMINANCE * abs(dy) -> if (dx > 0f) 0 else 2
        abs(dy) > AXIS_DOMINANCE * abs(dx) -> if (dy > 0f) 1 else 3
        else -> AMBIGUOUS
    }
}
