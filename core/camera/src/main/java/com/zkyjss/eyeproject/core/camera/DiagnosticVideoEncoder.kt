package com.zkyjss.eyeproject.core.camera

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 同一后台线程共享EGL上下文，以真实源帧时间送入硬件编码器，不使用Surface.lockCanvas。 */
internal class DiagnosticEgl : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: android.opengl.EGLContext
    private val program: Int
    private val vertices = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
    }
    init {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT)
        val pbuffer = EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it,source); GLES20.glCompileShader(it)
            val ok=IntArray(1); GLES20.glGetShaderiv(it,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0]!=0) { GLES20.glGetShaderInfoLog(it) }
        }
        val vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 uv; varying vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=uv;}")
        val fragment=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 t; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,t);}")
        program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val linked=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0); check(linked[0]!=0)
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,pbuffer)
    }
    fun window(surface: android.view.Surface): EGLSurface = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0).also { check(it != EGL14.EGL_NO_SURFACE) }
    fun texture(surface: EGLSurface): Int {
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        return IntArray(1).also { GLES20.glGenTextures(1,it,0) }[0]
    }
    fun draw(surface: EGLSurface, texture: Int, bitmap: Bitmap, width: Int, height: Int, timeUs: Long) {
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
        val pos=GLES20.glGetAttribLocation(program,"p"); val uv=GLES20.glGetAttribLocation(program,"uv")
        vertices.position(0); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(pos)
        vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        EGLExt.eglPresentationTimeANDROID(display,surface,timeUs*1000)
        check(EGL14.eglSwapBuffers(display,surface))
    }
    fun destroy(surface: EGLSurface) { EGL14.eglDestroySurface(display,surface) }
    override fun close() {
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
    }
}

internal class DiagnosticVideoEncoder(file: File, val width: Int, val height: Int, bitrate: Int, private val egl: DiagnosticEgl) : AutoCloseable {
    private val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private lateinit var surface: android.view.Surface
    private lateinit var window: EGLSurface
    private var texture: Int = 0
    private var track = -1; private var muxing=false; private var eos=false; private var closed=false
    private val info=MediaCodec.BufferInfo()
    private var writtenFrames = 0
    init {
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,bitrate); setInteger(MediaFormat.KEY_FRAME_RATE,12); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
        }
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface=codec.createInputSurface(); window=egl.window(surface); codec.start()
            texture=egl.texture(window)
            drain(false)
        } catch (error: Throwable) {
            runCatching { codec.release() }; runCatching { muxer.release() }
            if (::window.isInitialized) runCatching { egl.destroy(window) }
            if (::surface.isInitialized) runCatching { surface.release() }
            throw error
        }
    }
    fun append(bitmap: Bitmap, timeMs: Long) { check(!closed); drain(false); egl.draw(window,texture,bitmap,width,height,timeMs*1000); drain(false) }
    fun prime(bitmap: Bitmap) {
        append(bitmap, 0)
        val deadline = SystemClock.elapsedRealtime() + 1_000
        while (writtenFrames == 0) {
            drain(false)
            check(SystemClock.elapsedRealtime() < deadline) { "首帧编码超时 ${width}×${height}" }
            if (writtenFrames == 0) Thread.sleep(5)
        }
    }
    private fun drain(finishing: Boolean) {
        val deadline=SystemClock.elapsedRealtime()+3_000
        while (!eos) {
            when (val index=codec.dequeueOutputBuffer(info,if(finishing) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!finishing) return else check(SystemClock.elapsedRealtime()<deadline) { "视频编码器收尾超时" }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { check(!muxing); track=muxer.addTrack(codec.outputFormat); muxer.start(); muxing=true }
                else -> if (index>=0) {
                    val buffer=codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG !=0) info.size=0
                    if (info.size>0) { check(muxing); requireNotNull(buffer).position(info.offset); buffer.limit(info.offset+info.size); muxer.writeSampleData(track,buffer,info); writtenFrames++ }
                    eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0; codec.releaseOutputBuffer(index,false)
                }
            }
        }
    }
    override fun close() {
        if(closed) return; closed=true
        try { codec.signalEndOfInputStream(); drain(true) }
        finally { runCatching { codec.stop() }; codec.release(); egl.destroy(window); surface.release(); if(muxing) runCatching { muxer.stop() }; muxer.release() }
    }
}

/** 首选源尺寸；系统编码器拒绝时回退编码尺寸，眼区仍直接从源像素取样。 */
internal fun originalDiagnosticEncoder(file: File, bitmap: Bitmap, egl: DiagnosticEgl): DiagnosticVideoEncoder {
    val width = bitmap.width; val height = bitmap.height
    var failure: Throwable? = null
    val attempted = mutableSetOf<Pair<Int, Int>>()
    for (limit in listOf(maxOf(width, height), 1920, 1280)) {
        val scale = minOf(1.0, limit.toDouble() / maxOf(width, height))
        val w = (width * scale).toInt() / 2 * 2
        val h = (height * scale).toInt() / 2 * 2
        if (!attempted.add(w to h)) continue
        var candidate: DiagnosticVideoEncoder? = null
        try {
            candidate = DiagnosticVideoEncoder(file, w, h, 8_000_000, egl)
            candidate.prime(bitmap)
            return candidate
        } catch (error: Throwable) {
            failure = error
            runCatching { candidate?.close() }
        }
    }
    throw IllegalStateException("系统编码器拒绝原画与兼容尺寸 ${attempted.joinToString()}", failure)
}
