package com.zkyjss.eyeproject.core.camera

import android.graphics.*
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 画面配色由设计系统注入，导出画布字号使用固定视频像素。 */
data class DiagnosticVideoPalette(val background: Int, val text: Int, val secondary: Int, val target: Int,
    val left: Int, val right: Int, val reference: Int, val glint: Int)

/** 不在采集线程做拼接或编码。原画与诊断按同帧时间精确配对，最多排队一幅拷贝。 */
class StrabismusDiagnosticRecorder(private val root: File, private val palette: DiagnosticVideoPalette) {
    private val executor=ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>()) { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() },"斜视诊断视频")
    }.apply { allowCoreThreadTimeOut(true) }
    private val active=AtomicBoolean(false)
    private val credits=AtomicInteger(0)
    @Volatile private var submitted=-1L
    private var session: Session?=null
    private val copies = mutableListOf<Bitmap>()
    private val availableCopies = mutableListOf<Bitmap>()
    private fun releaseCopy(bitmap: Bitmap) = synchronized(copies) {
        if (!bitmap.isRecycled && bitmap !in availableCopies) availableCopies.add(bitmap)
    }
    private fun prepareCopies(width: Int, height: Int) = synchronized(copies) {
        // 首次大图分配也在后台完成；两幅缓冲分别用于编码与等待同帧诊断。
        availableCopies.filter { it.width != width || it.height != height }.toList().forEach {
            availableCopies.remove(it); copies.remove(it); it.recycle()
        }
        repeat((2 - copies.count { it.width == width && it.height == height }).coerceAtLeast(0)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            copies.add(bitmap); availableCopies.add(bitmap)
        }
    }

    fun event(json: String) {
        val row=runCatching { JSONObject(json) }.getOrNull() ?: return
        val type=row.optString("event")
        if(type=="start" || type=="end") { active.set(type=="start"); if(type=="start") submitted=-1L }
        executor.execute {
            runCatching {
                if(type=="start") { session?.let { finish(it,"新测试开始") }; session=Session(row) }
                val s=session ?: return@runCatching
                check(s.directory.usableSpace > 100_000_000) { "本机空间不足，诊断录制停止" }
                if(row.optString("sessionId")!=s.id) return@runCatching
                s.log.appendLine(json); s.log.flush()
                if(type=="frame") {
                    val stamp=row.getLong("captureTimeMs"); s.rows[stamp]=row
                    s.pending.remove(stamp)?.let { append(s,it,row,stamp) }
                    while(s.rows.size>24) s.rows.remove(s.rows.keys.min())
                } else if(type=="end") { s.manifest.put("audit",row); finish(s,row.optString("reason")); session=null }
            }.onFailure { error -> session?.let { fail(it,error) } }
        }
    }

    fun capture(bitmap: Bitmap, time: Long) {
        if(!active.get() || submitted>=0 && time-submitted<83 || !credits.compareAndSet(0,1)) return
        val copy = synchronized(copies) {
            availableCopies.firstOrNull { it.width == bitmap.width && it.height == bitmap.height }?.also { availableCopies.remove(it) }
        }
        if (copy == null) {
            val width = bitmap.width; val height = bitmap.height
            executor.execute {
                try { prepareCopies(width, height) }
                catch (error: Throwable) { session?.let { fail(it, error) } }
                finally { credits.set(0) }
            }
            return
        }
        submitted=time
        try { Canvas(copy).drawBitmap(bitmap, 0f, 0f, null) }
        catch (error: Throwable) { releaseCopy(copy); credits.set(0); return }
        executor.execute {
            try {
                val s=session ?: return@execute
                val first = s.raw == null
                if(first) {
                    s.base=time; s.egl=DiagnosticEgl()
                    s.raw=originalDiagnosticEncoder(File(s.directory,"original.mp4"),copy,s.egl!!)
                    s.video=DiagnosticVideoEncoder(File(s.directory,"comparison.mp4"),1280,720,3_000_000,s.egl!!)
                    s.manifest.put("sourceWidth",copy.width).put("sourceHeight",copy.height)
                        .put("originalWidth",s.raw!!.width).put("originalHeight",s.raw!!.height)
                    s.originalScaled = s.raw!!.width != copy.width || s.raw!!.height != copy.height
                    if (s.originalScaled) s.manifest.put("encodingNote", "原画编码器回退至${s.raw!!.width}×${s.raw!!.height}；眼区仍裁原像素")
                }
                if (!first) s.raw!!.append(copy,(time-s.base).coerceAtLeast(0))
                val row=s.rows.remove(time)
                if(row!=null) append(s,copy,row,time)
                else {
                    // 超过一帧的配对等待即明确标记分析缺失，不套用另一帧的检测或注视坐标。
                    s.pending.keys.toList().forEach { old -> s.pending.remove(old)?.let { append(s,it,missing(),old) } }
                    s.pending[time]=copy
                }
            } catch(error: Throwable) { session?.let { fail(it,error) } }
            finally {
                if(session?.pending?.values?.any { it===copy }!=true) releaseCopy(copy)
                credits.set(0)
            }
        }
    }
    private fun missing()=JSONObject().put("phase","无对应分析信息").put("valid",false)
        .put("reasons",JSONArray(listOf("帮助暂停、分析缺失或处理延迟")))
    private fun append(s: Session, bitmap: Bitmap, row: JSONObject, time: Long) {
        try {
            if(time<=s.last) { s.dropped++; return }
            val composite=s.renderer.render(bitmap,row,(time-s.base)/1000.0, s.originalScaled)
            s.video?.append(composite,(time-s.base).coerceAtLeast(0)); s.last=time; s.frames++
        } finally { releaseCopy(bitmap) }
    }
    private fun fail(s: Session,error: Throwable) {
        active.set(false); s.manifest.put("error",error.message ?: error.javaClass.simpleName)
        finish(s,"诊断录制失败，测试本身继续"); session=null
    }
    private fun finish(s: Session,reason: String) {
        if(s.finishing) return; s.finishing=true
        val finishStart = android.os.SystemClock.elapsedRealtime()
        s.pending.keys.sorted().forEach { time -> s.pending.remove(time)?.let { append(s,it,s.rows[time] ?: missing(),time) } }
        s.log.close(); s.manifest.put("state","finishing").put("reason",reason); s.save()
        var error: Throwable?=null
        for(writer in listOfNotNull(s.raw,s.video)) runCatching { writer.close() }.onFailure { error=it }
        runCatching { s.egl?.close() }.onFailure { error=it }
        s.renderer.close()
        val ready=error==null && s.frames>0 && !s.manifest.has("error")
        s.manifest.put("state",if(ready) "ready" else "failed").put("frames",s.frames).put("droppedFrames",s.dropped)
            .put("durationSeconds",((s.last-s.base)/1000.0).coerceAtLeast(0.0))
            .put("finalizationMs", android.os.SystemClock.elapsedRealtime() - finishStart)
        if(!ready && !s.manifest.has("error")) s.manifest.put("error",error?.message ?: "没有可编码的视频帧")
        s.save()
    }
    private inner class Session(row: JSONObject) {
        val id=row.getString("sessionId")
        val directory=File(root,id).apply { check(mkdirs() || isDirectory) }
        val manifest=JSONObject(row.toString()).put("state","recording").put("maxRecordingFps",12)
        val log=File(directory,"frames.jsonl").bufferedWriter()
        val renderer=DiagnosticPanelRenderer(palette)
        var egl: DiagnosticEgl?=null; var raw: DiagnosticVideoEncoder?=null; var video: DiagnosticVideoEncoder?=null
        var originalScaled = false
        var base=0L; var last=-1L; var frames=0; var dropped=0; var finishing=false
        val pending=linkedMapOf<Long,Bitmap>(); val rows=linkedMapOf<Long,JSONObject>()
        init { save() }
        fun save() { val target=File(directory,"manifest.json"); val temp=File(directory,"manifest.tmp"); temp.writeText(manifest.toString()); check(temp.renameTo(target)) }
    }
}

internal class DiagnosticPanelRenderer(private val p: DiagnosticVideoPalette) : AutoCloseable {
    private val output=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
    private val canvas=Canvas(output)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lastCrop=mutableMapOf<String,RectF>()
    private fun text(value: String,x: Float,y: Float,color: Int=p.text,size: Float=17f) {
        paint.color=color; paint.textSize=size; paint.style=Paint.Style.FILL
        canvas.drawText(value.take(90),x,y,paint)
    }
    private fun fit(width: Float,height: Float,rect: RectF): RectF {
        val scale=minOf(rect.width()/width.coerceAtLeast(1f),rect.height()/height.coerceAtLeast(1f))
        return RectF(rect.centerX()-width*scale/2,rect.centerY()-height*scale/2,rect.centerX()+width*scale/2,rect.centerY()+height*scale/2)
    }
    fun render(source: Bitmap,row: JSONObject,elapsed: Double, originalScaled: Boolean = false): Bitmap {
        canvas.drawColor(p.background)
        text(if (originalScaled) "原画 · 编码降采样 · 不镜像" else "原视频 · 不镜像",16f,30f)
        val full=fit(source.width.toFloat(),source.height.toFloat(),RectF(16f,48f,316f,618f))
        canvas.drawBitmap(source,null,full,paint)
        text("原始眼区放大",334f,30f); text("同帧映光检测",334f,345f)
        for((index,key) in listOf("left","right").withIndex()) {
            val eye=row.optJSONObject(key) ?: JSONObject(); val x=334f+300*index; val ink=if(index==0) p.left else p.right
            text(if(index==0) "受测者左眼" else "受测者右眼",x,65f,ink)
            val lid=eye.optJSONArray("lid")
            val inherited=lid==null || lid.length()<4
            val crop=if(!inherited) {
                val points=(0 until lid!!.length()).mapNotNull { lid.optJSONArray(it)?.let { a -> a.optDouble(0).toFloat() to a.optDouble(1).toFloat() } }
                val width=(points.maxOf { it.first }-points.minOf { it.first }).coerceAtLeast(8f)
                RectF(points.minOf { it.first }-width*.4f,points.minOf { it.second }-width*.4f,
                    points.maxOf { it.first }+width*.4f,points.maxOf { it.second }+width*.4f).takeIf {
                    it.intersect(0f,0f,source.width.toFloat(),source.height.toFloat())
                }?.also { lastCrop[key]=RectF(it) }
            } else lastCrop[key]
            if(crop==null || crop.width()<=0 || crop.height()<=0) { text("眼区不可定位",x,160f); continue }
            val raw=fit(crop.width(),crop.height(),RectF(x,78f,x+286,303f)); val overlay=RectF(raw).apply { offset(0f,280f) }
            val src=Rect(crop.left.toInt(),crop.top.toInt(),crop.right.toInt(),crop.bottom.toInt())
            canvas.drawBitmap(source,src,raw,paint); canvas.drawBitmap(source,src,overlay,paint)
            fun point(a: JSONArray): PointF = PointF(overlay.left+(a.optDouble(0).toFloat()-crop.left)*overlay.width()/crop.width(),
                overlay.top+(a.optDouble(1).toFloat()-crop.top)*overlay.height()/crop.height())
            canvas.save(); canvas.clipRect(overlay); paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f; paint.color=ink
            val glint = eye.optJSONArray("glint")
            glint?.let {
                val marker = point(it)
                paint.color = p.glint; paint.strokeWidth = 2f
                canvas.drawCircle(marker.x,marker.y,6f,paint)
            }
            canvas.restore()
            val confidence = eye.optDouble("glintConfidence")
            val summary = when {
                inherited -> "眼区定位暂缺"
                glint == null -> eye.optString("rejection", "未检测到映光点")
                !confidence.isFinite() -> "已检出 · 置信度未记录"
                else -> "置信度 ${(confidence * 100).toInt()}% · ${if (eye.optBoolean("glintCodeMatched")) "光码确认" else "候选亮斑"}"
            }
            text(summary,x,607f,ink,14f)
        }
        text("手机屏幕 · 全屏坐标",968f,30f)
        val phone=fit(row.optDouble("displayWidth",390.0).toFloat(),row.optDouble("displayHeight",844.0).toFloat(),RectF(972f,60f,1252f,600f))
        paint.color=p.background; canvas.drawRoundRect(phone,20f,20f,paint); paint.color=p.secondary; paint.style=Paint.Style.STROKE; paint.strokeWidth=2f
        canvas.drawRoundRect(phone,20f,20f,paint)
        for((key,color) in listOf("target" to p.target,"leftGaze" to p.left,"rightGaze" to p.right)) {
            if (key == "target" && !row.optBoolean("targetVisible", true)) continue
            val a=row.optJSONArray(key) ?: continue; val x=a.optDouble(0).toFloat(); val y=a.optDouble(1).toFloat()
            if(!x.isFinite() || !y.isFinite()) continue
            val px=phone.left+x.coerceIn(0f,1f)*phone.width(); val py=phone.top+y.coerceIn(0f,1f)*phone.height()
            paint.color=if (key == "target" && row.optString("targetColor") == "white") p.glint else color; paint.strokeWidth=2f
            if(key=="target") { paint.style=Paint.Style.FILL; canvas.drawCircle(px,py,7f,paint) }
            else { canvas.drawLine(px-6,py,px+6,py,paint); canvas.drawLine(px,py-6,px,py+6,paint)
                if(x !in 0f..1f || y !in 0f..1f) text("屏外估计",phone.left,phone.bottom+18,color,13f) }
        }
        paint.style=Paint.Style.FILL
        val usesIrisMotion = row.has("inputLeft") && !row.has("calibrated")
        text(if (usesIrisMotion) "实心：实际目标 · 眼部标注见左侧" else "实心：实际目标   青/粉：左/右注视估计",334f,640f,p.target,15f)
        val acquisition = if (usesIrisMotion) {
            if (row.optBoolean("valid")) "采集质量合格" else "本帧质量未通过"
        } else if (row.optString("phase") == "GAZE_CALIBRATION" && !row.optBoolean("calibrationFailed")) "标定中 · 入样 ${row.optInt("calibrationSamples")}"
            else if(row.optBoolean("calibrated")) "已标定" else "未获得可靠标定，不绘制估计"
        text("%.2f s  %s  %s  距离 %.1f cm  分析 %.1f 帧/秒".format(elapsed,(mapOf("GAZE_CALIBRATION" to "注视标定", "FIXATION" to "定点映光", "FAR_FIXATION" to "看远", "COVER" to "交替遮盖", "PURSUIT" to "移动点注视")[row.optString("phase")] ?: row.optString("phase")),acquisition,row.optDouble("distanceCm"),row.optDouble("fps")),16f,658f)
        text(row.optString("warning").takeUnless { it == "null" }.orEmpty(),16f,682f,p.secondary,14f)
        val reasons=row.optJSONArray("reasons")
        text(if(reasons==null || reasons.length()==0) "质量门控通过；标注只是诊断观测" else (0 until reasons.length()).joinToString("；") { reasons.optString(it) },16f,705f,p.secondary,14f)
        return output
    }
    override fun close() { output.recycle() }
}
