package com.zkyjss.eyeproject.feature.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 平台输入的容错：苹果逐帧内参抖动、TTS 读法。 */
class PlatformInputTest {
    @Test fun focalJitterDoesNotCountAsRecalibration() {
        assertTrue(focalChanged(0f, 1_000f))
        assertFalse(focalChanged(1_000f, 1_004f))
        assertFalse(focalChanged(1_000f, 985f))
        assertTrue(focalChanged(1_000f, 1_050f))
        assertFalse(focalChanged(1_000f, Float.NaN))
        assertFalse(focalChanged(1_000f, 0f))
    }

    @Test fun feedbackEventsSerializeAsJsonArray() {
        val json = RecordArray(listOf(RecordObject(mapOf("name" to "ANSWER", "microphonePauseMs" to 110)))).toString()
        assertEquals("""[{"name":"ANSWER","microphonePauseMs":110}]""", json)
    }

    @Test fun oneUtteranceAnswersOnlyOnce() {
        val guard = VoiceRepeatGuard(900)
        assertTrue(guard.accept(1_000))
        assertFalse(guard.accept(1_400))
        assertTrue(guard.accept(1_950))
    }

    @Test fun commonMishearingsOfRightAreAccepted() {
        for (text in listOf("右", "有", "又", "you")) assertEquals(VoiceCommand.Direction(0), VoiceCommandParser.parse(text), text)
    }

    @Test fun skipIsSpokenLikeTheSkipKey() {
        assertEquals(VoiceCommand.Unsure, VoiceCommandParser.parse("跳过"))
    }

    private fun calibration(skip: Set<Int> = emptySet(), lookAway: Set<Int> = emptySet()) =
        StrabismusGaze.calibrationTargets.flatMapIndexed { index, p ->
            if (index in skip) emptyList() else List(6) {
                val eye = if (index in lookAway) GazePoint(0.1f, -0.1f) else GazePoint((p.x - 0.5f) / 5, (p.y - 0.5f) / 3)
                GazeCalibrationSample(index, p, eye, eye)
            }
        }

    @Test fun threeLookAwayPointsAreDroppedWhenTheRestStillCoverTheCanvas() {
        assertTrue(StrabismusGaze.calibrate(calibration(lookAway = setOf(2, 4, 7))) != null)
    }

    @Test fun missingRowIsRetakenInsteadOfRestartingTheWholeCalibration() {
        val partial = calibration(skip = setOf(1, 2, 3))
        assertEquals(null, StrabismusGaze.calibrate(partial))
        assertEquals(listOf(1, 2, 3), StrabismusGaze.pointsToRetake(partial))
        // 补测后通过
        val retaken = partial + calibration().filter { it.index in setOf(1, 2, 3) }
        assertTrue(StrabismusGaze.calibrate(retaken) != null)
        // 大部分点不可用时不补测，整体重做
        assertEquals(emptyList(), StrabismusGaze.pointsToRetake(calibration(skip = setOf(1, 2, 3, 4, 5))))
    }

    /** iPhone 实测：屏幕窄、水平眼动信号弱，逐点固定偏差使水平均方根误差约 0.12 屏幕，仍应通过。 */
    @Test fun weakHorizontalSignalStillCalibratesButFailureSaysWhy() {
        val noisy = StrabismusGaze.calibrationTargets.flatMapIndexed { index, p ->
            val bias = if (index % 2 == 0) 0.025f else -0.025f
            List(6) {
                val eye = GazePoint((p.x - 0.5f) / 5 + bias, (p.y - 0.5f) / 3)
                GazeCalibrationSample(index, p, eye, eye)
            }
        }
        assertTrue(StrabismusGaze.calibrate(noisy) != null)
        val empty = calibration(skip = setOf(1, 2, 3, 4, 5))
        assertTrue(StrabismusGaze.failureReason(empty).startsWith("有 5 个圆点没有采到有效画面"))
    }

    @Test fun changedTargetDistanceAsksToMoveThereInsteadOfTooCloseOrFar() {
        val moving = ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.FLIPPER, trialIndex = 1,
            faceDetected = true, distanceCm = 50f, lux = 300f, distanceMoveToCm = 25f)
        assertEquals("请移动到 25 厘米处", screeningGuidance(moving))
        assertEquals("请移动到二十五厘米处。", screeningSpeechText(screeningGuidance(moving)!!))
        // 移动途中不发距离警示音
        assertEquals(3, screeningFeedbackFrame(moving).distanceZone)
        assertEquals("太远了，请把手机靠近一点", screeningGuidance(moving.copy(distanceMoveToCm = null)))
    }

    @Test fun coverPromptStaysSilentDuringHoldAndNamesOnlyTheNextAction() {
        val first = CoverMark("遮右眼", 0L, null, VisionEye.RIGHT)
        assertEquals("遮右眼", coverSpokenPrompt(ScreeningState(kind = ScreeningKind.STRABISMUS)))
        val holding = ScreeningState(kind = ScreeningKind.STRABISMUS, coverMarks = listOf(first), coverHolding = true)
        assertEquals("遮右眼", coverSpokenPrompt(holding))
        assertEquals("移开遮盖，双眼注视", coverSpokenPrompt(holding.copy(coverHolding = false)))
    }

    @Test fun optotypeLetterIsSpokenAsOptotype() {
        assertEquals("看视标选方向，同时像平时一样自然眨眼。", screeningSpeechText("看 E 选方向，同时像平时一样自然眨眼"))
        assertEquals("需要更大的视标才能看清。", screeningSpeechText("需要更大的 E 才能看清"))
        // 英文单词里的 E 不受影响
        assertEquals("EYE。", screeningSpeechText("EYE"))
    }
}
