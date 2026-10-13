package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/** 无需系统识别服务的离线中文指令后备；模型随 APK 打包，不上传录音。 */
internal class OfflineVoiceAnswer(private val context: Context, private val callbacks: VoiceAnswerRecognizer.Callbacks) {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var decoder: Recognizer? = null
    private var service: SpeechService? = null
    private var loading = false
    private var active = false
    private var closed = false
    private var generation = 0
    private val results = OfflineVoiceResults(callbacks::onHeard, callbacks::onCommand)

    fun start() {
        if (closed || active) return
        active = true
        if (model != null) { listen(); return }
        callbacks.onPreparing(true)
        if (loading) return
        loading = true
        loader.execute {
            val loaded = runCatching {
                val directory = File(context.noBackupFilesDir, MODEL_DIR)
                val ready = File(directory, ".ready")
                if (!ready.exists()) { copyAssets(MODEL_DIR, directory); ready.writeText("0.22") }
                try { Model(directory.absolutePath) } catch (error: Throwable) { ready.delete(); throw error }
            }
            main.post {
                loading = false
                if (closed) { loaded.getOrNull()?.close(); return@post }
                callbacks.onPreparing(false)
                loaded.onSuccess { model = it; if (active) listen() }
                    .onFailure { fail("离线语音初始化失败，请重试语音开关") }
            }
        }
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input -> destination.outputStream().use(input::copyTo) }
        } else {
            destination.mkdirs()
            children.forEach { copyAssets("$path/$it", File(destination, it)) }
        }
    }

    private fun listen() {
        if (!active || closed) return
        val token = ++generation
        results.reset()
        runCatching {
            if (decoder == null) decoder = Recognizer(model!!, 16_000f, GRAMMAR)
            decoder!!.reset()
            if (service == null) service = SpeechService(decoder!!, 16_000f)
            check(service!!.startListening(object : org.vosk.android.RecognitionListener {
                private fun current() = active && !closed && token == generation
                override fun onPartialResult(hypothesis: String) { if (current()) results.accept(hypothesis, false) }
                override fun onResult(hypothesis: String) { if (current()) results.accept(hypothesis, true) }
                override fun onFinalResult(hypothesis: String) = Unit
                override fun onError(exception: Exception) { if (current()) fail("无法使用麦克风，请检查权限和麦克风占用") }
                override fun onTimeout() { if (current()) { stop(); start() } }
            }))
            callbacks.onListening(true)
        }.onFailure { fail("离线语音无法启动，请检查麦克风权限后重试") }
    }

    private fun fail(reason: String) { stop(); callbacks.onPreparing(false); callbacks.onUnavailable(reason) }

    fun stop() {
        active = false; generation++; results.reset()
        service?.cancel()
        callbacks.onListening(false)
    }

    fun destroy() {
        closed = true; stop(); service?.shutdown(); service = null
        decoder?.close(); decoder = null; model?.close(); model = null
        loader.shutdown()
    }

    companion object {
        const val MODEL_DIR = "vosk-model-small-cn-0.22"
        // 短指令词表减少推理负担；[unk] 保留拒绝无关声音的路径。
        val GRAMMAR: String = JSONArray(listOf("上", "下", "左", "右", "向 上", "向 下", "向 左", "向 右",
            "看 不 清", "不 知道", "模糊", "好 了", "开始", "[unk]")).toString()
    }
}

/** 部分结果尽早响应，同一句的最终结果不重复作答；下一句话仍可重复同一个方向。 */
internal class OfflineVoiceResults(private val heard: (String) -> Unit, private val command: (VoiceCommand) -> Unit) {
    private var fired = false
    fun reset() { fired = false }
    fun accept(json: String, final: Boolean) {
        val text = runCatching { JSONObject(json).optString(if (final) "text" else "partial") }.getOrDefault("")
        if (text.isNotBlank()) heard(text)
        if (!fired && !text.contains("[unk]")) VoiceCommandParser.parse(text)?.let { fired = true; command(it) }
        if (final) fired = false
    }
}
