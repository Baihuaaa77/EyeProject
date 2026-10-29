package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 护眼操：看到眼球沿提示方向转过去才算完成；看不到脸时按时间带着做。 */
class EyeExerciseCoachTest {
    /** 眼裂宽 100 px，虹膜中心相对中点偏移 (dx, dy) px（图像坐标：受测者的左边在 +x）。 */
    private fun face(dx: Float, dy: Float): FaceMetrics {
        fun eye(cx: Float, label: String) = EyeLandmarks(label, listOf(
            Point2f(cx - 50f, 600f), Point2f(cx - 20f, 590f), Point2f(cx + 20f, 590f),
            Point2f(cx + 50f, 600f), Point2f(cx + 20f, 610f), Point2f(cx - 20f, 610f)),
            listOf(Point2f(cx + dx, 600f + dy)), 30f)
        return FaceMetrics(BoundingBox(100f, 400f, 600f, 900f), eye(450f, "LEFT"), eye(250f, "RIGHT"))
    }

    @Test fun detectsEachDirectionAfterCalibration() {
        val coach = EyeExerciseCoach(listOf(EyeExerciseCoach.Move.LEFT, EyeExerciseCoach.Move.UP))
        var t = 0L
        fun run(ms: Long, dx: Float, dy: Float): List<EyeExerciseCoach.Event> = buildList {
            val end = t + ms
            while (t < end) { coach.onFace(face(dx, dy), t); coach.tick(t)?.let(::add); t += 33 }
        }
        assertTrue(EyeExerciseCoach.Event.NEXT in run(1_600, 0f, 0f), "看着小瞳 1.5 秒后进入第一步")
        assertEquals(EyeExerciseCoach.Move.LEFT, coach.state(t).move)
        // 受测者向左看：虹膜在图像里往 +x 偏 12 px（眼裂宽的 12%）
        assertTrue(EyeExerciseCoach.Event.DETECTED in run(600, 12f, 0f))
        run(1_300, 0f, 0f)
        assertEquals(EyeExerciseCoach.Move.UP, coach.state(t).move)
        assertTrue(EyeExerciseCoach.Event.DETECTED !in run(1_000, 0f, 5f), "往下看不算向上")
        assertTrue(EyeExerciseCoach.Event.DETECTED in run(600, 0f, -6f))
        run(1_300, 0f, 0f)
        assertEquals(EyeExerciseCoach.Stage.REST, coach.state(t).stage)
        assertEquals(2 to 2, coach.summary)
    }

    @Test fun withoutAFaceItGuidesByTime() {
        val coach = EyeExerciseCoach(listOf(EyeExerciseCoach.Move.RIGHT))
        val events = mutableListOf<EyeExerciseCoach.Event>()
        var t = 0L
        while (t < 20_000) { coach.tick(t)?.let(events::add); t += 50 }
        // 闭眼 5 秒：剩 4、3、2、1 秒各报一次倒计时，再结束
        val countdown = List(4) { EyeExerciseCoach.Event.COUNTDOWN }
        assertEquals(listOf(EyeExerciseCoach.Event.STARTED, EyeExerciseCoach.Event.NEXT, EyeExerciseCoach.Event.NEXT,
            EyeExerciseCoach.Event.REST) + countdown + EyeExerciseCoach.Event.DONE, events)
        assertEquals(1 to 0, coach.summary)
        assertEquals(1f, coach.state(t).progress)
    }
}
