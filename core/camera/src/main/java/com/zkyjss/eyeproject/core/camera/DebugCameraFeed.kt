package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Debug 模拟器专用：接收电脑摄像头画面的本地套接字。
 *
 * 电脑端 `tools/show_camera_feed.py` 通过 `adb forward tcp:N localabstract:[SOCKET_NAME]`
 * 建立一条长连接，持续发送「4 字节大端长度 + JPEG」帧；这里只保留最新一帧。
 * 取代旧方案（每帧两次 adb 进程 + 写文件 + 分析器反复解码同一张文件），
 * 大幅降低电脑和模拟器两端的 CPU 占用，并且没有新帧时分析器不再重复推理。
 *
 * 只在可调试包 + 模拟器上启动；正式包与真机不会监听任何套接字。
 */
object DebugCameraFeed {
    const val SOCKET_NAME = "eyeproject_camera_feed"
    private const val TAG = "DebugCameraFeed"
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    /** 超过该时长未收到新帧即视为电脑摄像头输入已停止，回到模拟器自带相机。 */
    private const val STALE_MS = 1_500L

    private val started = AtomicBoolean(false)
    @Volatile private var latest: ByteArray? = null
    @Volatile private var sequence = 0L
    @Volatile private var lastFrameMs = 0L

    fun supported(context: Context): Boolean {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
        return debuggable && emulator
    }

    fun ensureStarted(context: Context) {
        if (!supported(context) || !started.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "debug-camera-feed") {
            try {
                LocalServerSocket(SOCKET_NAME).use { server ->
                    while (true) receive(server.accept())
                }
            } catch (e: Exception) {
                Log.w(TAG, "电脑摄像头输入通道不可用", e)
                started.set(false)
            }
        }
    }

    /** 电脑摄像头画面是否正在送入。 */
    fun active(): Boolean = latest != null && SystemClock.elapsedRealtime() - lastFrameMs < STALE_MS

    /** 返回比 [afterSequence] 更新的帧；没有新帧时返回 null（调用方应跳过本次推理）。 */
    fun takeIfNewer(afterSequence: Long): Pair<Long, ByteArray>? {
        if (!active()) return null
        val seq = sequence
        val bytes = latest ?: return null
        return if (seq > afterSequence) seq to bytes else null
    }

    private fun receive(socket: LocalSocket) {
        socket.use {
            val input = DataInputStream(BufferedInputStream(it.inputStream, 256 * 1024))
            try {
                while (true) {
                    val length = input.readInt()
                    if (length <= 0 || length > MAX_FRAME_BYTES) return
                    val frame = ByteArray(length)
                    input.readFully(frame)
                    latest = frame
                    lastFrameMs = SystemClock.elapsedRealtime()
                    sequence++
                }
            } catch (_: EOFException) {
                // 电脑端关闭窗口：等待下一次连接
            } catch (e: Exception) {
                Log.w(TAG, "电脑摄像头输入连接中断", e)
            } finally {
                latest = null
            }
        }
    }
}
