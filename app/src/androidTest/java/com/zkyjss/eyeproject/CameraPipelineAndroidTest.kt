package com.zkyjss.eyeproject

import android.Manifest
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.camera.DebugCameraFeed
import java.io.DataOutputStream
import kotlin.math.tan
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 检查 CameraX 前摄到 MediaPipe 回调的完整路径；无需画面中有人脸。 */
@RunWith(AndroidJUnit4::class)
class CameraPipelineAndroidTest {
    @Test
    fun hostCameraFeedSwitchesToFieldOfViewFocal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = CameraIntrinsicsProvider(context)
        val landscape = provider.focalLengthPx(640, 480)
        val portrait = provider.focalLengthPx(480, 640)
        assertTrue("模拟器前摄缺少焦距参数", landscape > 0f)
        assertEquals("旋转不应改变焦距像素值", landscape, portrait, 0.01f)

        // 通过与电脑端相同的套接字协议送入一帧，焦距应改按固定视场角估算
        DebugCameraFeed.ensureStarted(context)
        // 服务端在后台线程启动：每次用新套接字重试连接
        var socket: LocalSocket? = null
        for (attempt in 0 until 20) {
            val candidate = LocalSocket()
            if (runCatching { candidate.connect(LocalSocketAddress(DebugCameraFeed.SOCKET_NAME)) }.isSuccess) {
                socket = candidate
                break
            }
            candidate.close()
            Thread.sleep(100)
        }
        requireNotNull(socket) { "无法连接电脑摄像头输入通道" }.use { connected ->
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            DataOutputStream(connected.outputStream).apply { writeInt(jpeg.size); write(jpeg); flush() }
            var remaining = 30
            while (!DebugCameraFeed.active() && remaining-- > 0) Thread.sleep(50)
            assertTrue("电脑摄像头输入未生效", DebugCameraFeed.active())
            fun expected(longSide: Int, hfov: Double) = (longSide / 2f) / tan(Math.toRadians(hfov / 2)).toFloat()
            assertEquals("16:9 画面应按 65° 估算", expected(1280, CameraIntrinsicsProvider.HOST_HFOV_DEG),
                provider.focalLengthPx(1280, 720), 0.5f)
            assertEquals("4:3 画面应按裁切后的约 51° 估算", expected(640, CameraIntrinsicsProvider.HOST_HFOV_4_3_DEG),
                provider.focalLengthPx(640, 480), 0.5f)
        }
    }

    @Test
    fun frontCameraProducesAnalyzedFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)

        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        val camera = CameraController(context)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        try {
            instrumentation.runOnMainSync {
                owner.registry.currentState = Lifecycle.State.RESUMED
                camera.start(
                    lifecycleOwner = owner,
                    onFrame = { completed.countDown() },
                    onError = { error -> failure.set(error); completed.countDown() },
                )
            }
            assertTrue("前摄与人脸关键点管线 45 秒内没有返回帧", completed.await(45, TimeUnit.SECONDS))
            failure.get()?.let { fail("前摄分析失败：${it.message}") }
        } finally {
            instrumentation.runOnMainSync {
                camera.stop()
                owner.registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }
}
