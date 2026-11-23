package com.zkyjss.eyeproject.core.camera

import kotlin.math.abs
import kotlin.math.hypot

/** 从使用者视角判断单手食指朝向。坐标以图像长边归一化，x 向右、y 向下。 */
object HandPointing {
    const val NEUTRAL = -1
    const val AMBIGUOUS = -2

    /** 遮盖单眼的手可能也在画面中。仅在恰好一只手明确指向时接受方向。 */
    fun selectDirection(hands: IntArray): Int {
        val pointing = hands.filter { it in 0..3 }
        return when {
            pointing.size == 1 -> pointing.single()
            pointing.isNotEmpty() || hands.any { it == AMBIGUOUS } -> AMBIGUOUS
            else -> NEUTRAL
        }
    }

    /** 依次传手腕，以及食指到小指各自的掌指关节、中间关节和指尖（每点 x、y）。 */
    fun direction(points: FloatArray): Int {
        if (points.size != 26 || points.any { !it.isFinite() }) return AMBIGUOUS
        fun distance(a: Int, b: Int): Float =
            hypot(points[2 * a] - points[2 * b], points[2 * a + 1] - points[2 * b + 1])

        val palm = distance(0, 4)
        if (palm < 0.025f) return AMBIGUOUS
        val indexLength = distance(1, 2) + distance(2, 3)
        val indexReach = distance(1, 3)
        if (indexLength < 0.55f * palm || indexReach < 0.88f * indexLength ||
            distance(0, 3) < distance(0, 2) + 0.12f * palm) return NEUTRAL
        // 其余三指中有一根明显伸展，就不作为单指作答。
        if (otherFingers.any { joint ->
                distance(0, joint + 2) > distance(0, joint + 1) + 0.08f * palm
            }) return NEUTRAL

        val dx = points[6] - points[2]
        val dy = points[7] - points[3]
        return when {
            abs(dx) > 1.6f * abs(dy) -> if (dx > 0) 0 else 2
            abs(dy) > 1.6f * abs(dx) -> if (dy > 0) 1 else 3
            else -> AMBIGUOUS
        }
    }

    private val otherFingers = intArrayOf(4, 7, 10)
}
