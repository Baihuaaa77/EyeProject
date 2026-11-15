package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** 查看方法时冻结测试时钟、定时等待与相机采样；系统时间与记录保存仍使用真实时间。 */
internal class TestHelpSession(private val source: ScreeningPlatform) : ScreeningPlatform by source {
    private data class Clock(val pausedAt: Long? = null, val excludedMs: Long = 0)
    private val clock = MutableStateFlow(Clock())
    val paused: Boolean get() = clock.value.pausedAt != null

    fun setPresented(presented: Boolean) {
        val previous = clock.value
        if (presented == (previous.pausedAt != null)) return
        val now = source.nowMs()
        clock.value = if (presented) previous.copy(pausedAt = now)
        else Clock(excludedMs = previous.excludedMs + (now - previous.pausedAt!!).coerceAtLeast(0))
    }

    override fun nowMs(): Long = clock.value.let { (it.pausedAt ?: source.nowMs()) - it.excludedMs }
    fun presentationTime(rawMs: Long): Long = clock.value.let { minOf(rawMs, it.pausedAt ?: rawMs) - it.excludedMs }

    override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) {
        source.startCamera({ frame ->
            if (!paused) onFrame(frame.copy(analysisElapsedRealtimeMs = frame.analysisElapsedRealtimeMs?.let(::presentationTime)))
        }, onError)
    }

    suspend fun awaitResumed() { clock.first { it.pausedAt == null } }

    /** 等待按有效测试时间推进，包含原本剩余的一小段等待，不把查看演示计入超时。 */
    suspend fun delay(milliseconds: Long) {
        val until = nowMs() + milliseconds.coerceAtLeast(0)
        while (true) {
            awaitResumed()
            val version = clock.value
            val remaining = until - nowMs()
            if (remaining <= 0) return
            // 暂停状态变化时重算剩余时间；不为等待演示额外启动轮询。
            kotlinx.coroutines.withTimeoutOrNull(remaining) { clock.first { it != version } }
        }
    }

    suspend fun <T> withTimeoutOrNull(milliseconds: Long, block: suspend () -> T): T? = coroutineScope {
        val value = async { block() }
        val timeout = async { delay(milliseconds) }
        try { select { value.onAwait { it }; timeout.onAwait { null } } }
        finally { value.cancel(); timeout.cancel() }
    }
}
