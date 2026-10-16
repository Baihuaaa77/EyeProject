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
