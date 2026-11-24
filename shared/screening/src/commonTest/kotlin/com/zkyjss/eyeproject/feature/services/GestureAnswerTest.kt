package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.HandPointing
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureAnswerTest {
    @Test fun pointingMatchesTheOptotypeDirectionCodes() {
        val up = floatArrayOf(
            0f, 0f,
            -0.022f, -0.068f, -0.025f, -0.101f, -0.027f, -0.146f,
            0f, -0.075f, 0f, -0.102f, 0.005f, -0.082f,
            0.019f, -0.071f, 0.022f, -0.095f, 0.017f, -0.079f,
            0.039f, -0.065f, 0.04f, -0.086f, 0.032f, -0.07f,
        )
        assertEquals(3, HandPointing.direction(up))
        assertEquals(0, HandPointing.direction(transform(up) { x, y -> -y to x }))
        assertEquals(1, HandPointing.direction(transform(up) { x, y -> -x to -y }))
        assertEquals(2, HandPointing.direction(transform(up) { x, y -> y to -x }))
        assertEquals(HandPointing.AMBIGUOUS, HandPointing.direction(transform(up) { x, y -> (x - y) to (x + y) }))

        // 自然斜指约 30° 仍应稳定识别为向上，而不是被过窄扇区拒判。
        val tilted30 = rotate(up, 30.0)
        assertEquals(3, HandPointing.direction(tilted30))

        // 其余三指自然半弯（未死攥成拳）仍应识别为食指指向。
        val relaxedIndexUp = up.copyOf().apply {
            this[12] = 0.004f; this[13] = -0.109f // 中指略高于 PIP 但远未伸直
        }
        assertEquals(3, HandPointing.direction(relaxedIndexUp))

        // 中指与食指一样伸直（如张开手掌遮眼）必须拒判。
        val openHand = up.copyOf().apply { this[13] = -0.144f }
        assertEquals(HandPointing.NEUTRAL, HandPointing.direction(openHand))

        assertEquals(2, HandPointing.selectDirection(intArrayOf(HandPointing.NEUTRAL, 2)))
        assertEquals(2, HandPointing.selectDirection(intArrayOf(HandPointing.AMBIGUOUS, 2)))
        assertEquals(0, HandPointing.selectDirection(intArrayOf(0, 0)))
        assertEquals(HandPointing.AMBIGUOUS, HandPointing.selectDirection(intArrayOf(0, 2)))
        assertEquals(HandPointing.NEUTRAL, HandPointing.selectDirection(intArrayOf(HandPointing.AMBIGUOUS, HandPointing.NEUTRAL)))
    }

    @Test fun thumbPointingAndOccludedCurledFingersAreSupported() {
        // 17 点（含大拇指）：握拳且大拇指明确朝上（其余四指蜷缩）。
        val thumbUp = floatArrayOf(
            0f, 0f, // 0 wrist
            -0.024f, -0.022f, -0.042f, -0.052f, -0.046f, -0.082f, -0.048f, -0.118f, // 1..4 thumb
            -0.010f, -0.070f, 0.010f, -0.072f, 0.002f, -0.055f, // 5..7 index curled
            0.008f, -0.068f, 0.025f, -0.066f, 0.014f, -0.050f, // 8..10 middle curled
            0.022f, -0.060f, 0.036f, -0.058f, 0.024f, -0.044f, // 11..13 ring curled
            0.034f, -0.050f, 0.044f, -0.046f, 0.034f, -0.036f, // 14..16 pinky curled
        )
        assertEquals(3, HandPointing.direction(thumbUp))
        assertEquals(0, HandPointing.direction(transform(thumbUp) { x, y -> -y to x }))
        assertEquals(1, HandPointing.direction(transform(thumbUp) { x, y -> -x to -y }))
        assertEquals(2, HandPointing.direction(transform(thumbUp) { x, y -> y to -x }))

        // 握拳但大拇指贴在食指旁（未伸出）不应误触发。
        val closedFist = thumbUp.copyOf().apply {
            this[6] = -0.024f; this[7] = -0.062f
            this[8] = -0.012f; this[9] = -0.068f
        }
        assertEquals(HandPointing.NEUTRAL, HandPointing.direction(closedFist))

        // 被掌心遮挡的蜷缩指尖（如 iOS Vision 低置信度转为 NaN）不误杀大拇指指向。
        val occludedCurled = thumbUp.copyOf().apply {
            this[26] = Float.NaN; this[27] = Float.NaN // ringTip 缺失
            this[32] = Float.NaN; this[33] = Float.NaN // pinkyTip 缺失
        }
        assertEquals(3, HandPointing.direction(occludedCurled))
    }

    @Test fun requiresAStableGestureAfterPresentationAndAReleaseBetweenSameDirectionQuestions() {
        val gate = GestureAnswerGate()
        fun frame(direction: Int, time: Long, trial: Int = 1, shown: Long? = 1_350L) =
            gate.offer(direction, time, time + 10L, trial, shown)
        for (time in listOf(1_000L, 1_090L, 1_180L)) assertNull(frame(-1, time, shown = null))
        assertNull(frame(3, 1_270))
        assertNull(frame(3, 1_360))
        assertEquals(3, gate.feedback.direction)
        assertNull(frame(3, 1_450))
        assertTrue(gate.feedback.progress in 0.4f..0.6f)
        assertEquals(3, frame(3, 1_540))

        // 同方向持续举手绝不自动回答下一题，并给出待回手反馈。
        for (time in listOf(1_630L, 1_720L, 1_810L, 1_900L)) assertNull(frame(3, time, 2, 1_700))
        assertEquals(3, gate.feedback.direction)
        assertTrue(gate.feedback.needsRelease)

        // 短暂放下手解锁后，可再次同向作答。
        for (time in listOf(1_990L, 2_080L, 2_170L)) assertNull(frame(-1, time, 2, 1_700))
        assertNull(frame(3, 2_260, 2, 1_700))
        assertFalse(gate.feedback.needsRelease)
        assertNull(frame(3, 2_350, 2, 1_700))
        assertEquals(3, frame(3, 2_440, 2, 1_700))
    }

    @Test fun switchingDirectionAnswersNextQuestionWithoutLoweringHand() {
        val gate = GestureAnswerGate()
        fun frame(direction: Int, time: Long, trial: Int, shown: Long) =
            gate.offer(direction, time, time + 10L, trial, shown)

        // 第一题直接指向右（无需先等放手）。
        assertNull(frame(0, 1_000, 1, 1_000))
        assertNull(frame(0, 1_090, 1, 1_000))
        assertEquals(0, frame(0, 1_180, 1, 1_000))

        // 第二题呈现后直接转向左，无需先把手放下即可续答。
        assertNull(frame(2, 1_500, 2, 1_480))
        assertFalse(gate.feedback.needsRelease)
        assertNull(frame(2, 1_590, 2, 1_480))
        assertEquals(2, frame(2, 1_680, 2, 1_480))
    }

    @Test fun toleratesSingleFrameGlitchWithoutCountingBlankTime() {
        val gate = GestureAnswerGate()
        fun frame(direction: Int, time: Long) = gate.offer(direction, time, time + 10L, 1, 1_000L)

        assertNull(frame(1, 1_000)) // held = 0
        assertNull(frame(1, 1_060)) // held = 60
        assertNull(frame(HandPointing.AMBIGUOUS, 1_120)) // 单帧抖动，保留 60ms 进度
        assertEquals(1, gate.feedback.direction)
        assertNull(frame(1, 1_180)) // 恢复指向，不把空白算作保持时长（仍为 60ms）
        assertNull(frame(1, 1_240)) // held = 120
        assertEquals(1, frame(1, 1_300)) // held = 180 -> 确认
    }

    @Test fun dropsStaleFramesAndNeverCarriesAConfirmationIntoTheNextQuestion() {
        val gate = GestureAnswerGate()
        fun frame(direction: Int, time: Long, trial: Int = 1, lag: Long = 15L) =
            gate.offer(direction, time, time + lag, trial, 1_000L)
        for (time in listOf(1_000L, 1_090L, 1_180L)) assertNull(frame(-1, time))
        assertNull(frame(2, 1_270))
        assertNull(frame(2, 1_360))
        assertNull(frame(2, 1_450, lag = 600))
        assertNull(frame(2, 1_460, trial = 2))
        assertNull(frame(2, 1_450, trial = 2))
        assertNull(frame(2, 1_550, trial = 2))
        assertEquals(2, frame(2, 1_640, trial = 2))
    }

    private fun rotate(points: FloatArray, degrees: Double): FloatArray {
        val rad = degrees * kotlin.math.PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return transform(points) { x, y -> (x * c - y * s) to (x * s + y * c) }
    }

    private fun transform(points: FloatArray, project: (Float, Float) -> Pair<Float, Float>): FloatArray {
        val result = points.copyOf()
        for (index in points.indices step 2) {
            val (x, y) = project(points[index], points[index + 1])
            result[index] = x
            result[index + 1] = y
        }
        return result
    }
}
