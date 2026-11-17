package com.zkyjss.eyeproject

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zkyjss.eyeproject.core.camera.DiagnosticVideoPalette
import com.zkyjss.eyeproject.core.camera.StrabismusDiagnosticRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用真实系统编码器检验原画、拼接、时间轴和结束延迟；合成图不作为检测准确性证据。 */
@RunWith(AndroidJUnit4::class)
class StrabismusDiagnosticAndroidTest {
    @Test fun encodesAlignedMoviesAndFinishesWithoutWholeVideoReprocessing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "diagnostic-encoding-test").apply { mkdirs() }
        val id = "strabismus_${System.currentTimeMillis()}_0"
        val directory = File(root, id)
        val recorder = StrabismusDiagnosticRecorder(root, DiagnosticVideoPalette(Color.BLACK, Color.WHITE,
            Color.LTGRAY, Color.rgb(255, 140, 40), Color.CYAN, Color.MAGENTA, Color.GREEN, Color.WHITE))
        fun event(type: String) = JSONObject().put("event", type).put("sessionId", id)
        recorder.event(event("start").put("createdAtMs", System.currentTimeMillis()).toString())
        val source = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        val lid = JSONArray("[[210,190],[230,180],[260,180],[280,190],[260,200],[230,200]]")
        for (i in 0 until 24) {
            // 上红下蓝，能发现编码画面倒置；每帧不同以发现停帧。
            canvas.drawColor(Color.BLUE); paint.color = Color.RED; canvas.drawRect(0f, 0f, 640f, 240f, paint)
            paint.color = Color.WHITE; canvas.drawCircle(80f + i * 10, 120f, 16f, paint)
            val time = 10_000L + i * 100
            recorder.capture(source, time)
            val eye = JSONObject().put("lid", lid).put("iris", JSONArray("[[245,190],[240,190],[245,185],[250,190],[245,195]]"))
                .put("pupil", JSONArray("[245,190]")).put("glint", JSONArray("[249,189]")).put("rejection", "合成画面")
            recorder.event(event("frame").put("captureTimeMs", time).put("phase", "PURSUIT")
                .put("displayWidth", 1080).put("displayHeight", 2400).put("target", JSONArray("[0.5,0.25]"))
                .put("leftGaze", JSONArray("[0.4,0.3]")).put("rightGaze", JSONArray("[0.6,0.4]"))
                .put("calibrated", true).put("valid", true).put("left", eye).put("right", eye).toString())
            SystemClock.sleep(100)
        }
        source.recycle()
        val finishStart = SystemClock.elapsedRealtime()
        recorder.event(event("end").put("reason", "编码回归测试").toString())
        val manifestFile = File(directory, "manifest.json")
        var manifest = JSONObject()
        while (SystemClock.elapsedRealtime() - finishStart < 8_000) {
            manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrDefault(JSONObject())
            if (manifest.optString("state") in listOf("ready", "failed")) break
            SystemClock.sleep(50)
        }
        val waitMs = SystemClock.elapsedRealtime() - finishStart
        assertEquals(manifest.toString(), "ready", manifest.optString("state"))
        assertTrue("编码帧过少：$manifest", manifest.getInt("frames") >= 8)
        for ((name, width, height) in listOf(Triple("original.mp4", 640, 480), Triple("comparison.mp4", 1280, 720))) {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(File(directory, name).absolutePath)
                assertEquals(width.toString(), reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                assertEquals(height.toString(), reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 700)
                val frame = reader.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                if (name == "original.mp4") {
                    assertTrue("原画上下倒置", Color.red(frame.getPixel(20, 20)) > 180)
                    assertTrue("原画上下倒置", Color.blue(frame.getPixel(20, 460)) > 180)
                } else File(directory, "preview.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            } finally { reader.release() }
        }
        assertEquals(26, File(directory, "frames.jsonl").readLines().size)
        File(directory, "performance.txt").writeText("结束等待 ${waitMs}ms；视频帧数 ${manifest.getInt("frames")}；测试源 640x480/10fps")
    }
}
