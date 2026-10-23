package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 首页实时视窗：与干眼测试同一眨眼判据、曲线窗口裁剪、帧率与断流判断。 */
class LiveEyeMonitorTest {
    /** 眼裂宽 1、上下各偏 h 的 6 点，EAR = 2h。 */
    private fun eye(ear: Float, label: String) = EyeLandmarks(label, listOf(
        Point2f(0f, 0f), Point2f(0.3f, -ear / 2), Point2f(0.7f, -ear / 2),
        Point2f(1f, 0f), Point2f(0.7f, ear / 2), Point2f(0.3f, ear / 2)), emptyList(), 20f)

    private fun face(ear: Float) = FaceMetrics(BoundingBox(0f, 0f, 1f, 1f), eye(ear, "LEFT"), eye(ear, "RIGHT"))

    @Test fun countsBlinksWithTheDryEyeRuleAndKeepsAWindow() {
        val monitor = LiveEyeMonitor(windowMs = 2_000)
        var t = 0L
        repeat(30) { monitor.update(face(0.3f), 720, 1280, t); t += 33 }
        assertTrue(monitor.snapshot(t).ready)
        // 闭眼 3 帧（约 100 ms）后睁开：记一次
        repeat(3) { monitor.update(face(0.05f), 720, 1280, t); t += 33 }
        assertTrue(monitor.snapshot(t).closed)
        monitor.update(face(0.3f), 720, 1280, t)
        val afterBlink = monitor.snapshot(t)
        assertEquals(1, afterBlink.blinkCount)
        assertEquals(listOf(t), afterBlink.blinkTimesMs)
        assertEquals(99L, afterBlink.lastClosedMs)
        assertFalse(afterBlink.closed)
        // 窗口只留最近 2 秒，帧率按最近 1 秒计
        t += 33
        repeat(90) { monitor.update(face(0.3f), 720, 1280, t); t += 33 }
        val later = monitor.snapshot(t)
        assertTrue(later.samples.first().timeMs >= later.samples.last().timeMs - 2_000)
        assertTrue(later.blinkTimesMs.isEmpty(), "眨眼已移出曲线窗口")
        assertEquals(1, later.blinksLastMinute)
        assertTrue(later.fps in 29f..31f)
    }

    @Test fun reportsMissingEyesAndStaleCamera() {
        val monitor = LiveEyeMonitor()
        assertTrue(monitor.snapshot(0).waiting)
        monitor.update(null, 720, 1280, 1_000)
        val noFace = monitor.snapshot(1_100)
        assertFalse(noFace.faceFound); assertFalse(noFace.eyesFound); assertNull(noFace.ear)
        assertFalse(noFace.stale)
        assertTrue(monitor.snapshot(1_000 + LiveEyeMonitor.STALE_MS + 1).stale, "超过 1.5 秒没有新帧视为画面中断")
        monitor.reset()
        assertTrue(monitor.snapshot(5_000).waiting)
    }
}
