package com.zkyjss.eyeproject

import android.Manifest
import android.graphics.BitmapFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 检查 CameraX 前摄到 MediaPipe 回调的完整路径；无需画面中有人脸。 */
@RunWith(AndroidJUnit4::class)
class CameraPipelineAndroidTest {
    @Test
    fun debugCameraDistanceScaleUsesInjectedCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = CameraIntrinsicsProvider(context)
        val input = File(context.filesDir, "camera_debug_input.jpg")
        val scale = File(context.filesDir, "camera_debug_focal_scale.txt")
        val previousInput = input.takeIf { it.exists() }?.readBytes()
        val previousScale = scale.takeIf { it.exists() }?.readBytes()
        try {
            input.delete()
            val landscape = provider.focalLengthPx(640, 480)
            val portrait = provider.focalLengthPx(480, 640)
            assertTrue("模拟器前摄缺少焦距参数", landscape > 0f)
            assertEquals("旋转不应改变焦距像素值", landscape, portrait, 0.01f)
            input.writeBytes(byteArrayOf(1))
            scale.writeText("1.5")
            assertEquals("电脑摄像头标定系数未生效", landscape * 1.5f,
                provider.focalLengthPx(640, 480), 0.01f)
        } finally {
            if (previousInput == null) input.delete() else input.writeBytes(previousInput)
            if (previousScale == null) scale.delete() else scale.writeBytes(previousScale)
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
        val debugSwitch = File(context.filesDir, "camera_debug.enable")
        val debugFrame = File(context.filesDir, "camera_debug_frame.jpg")
        val debugInput = File(context.filesDir, "camera_debug_input.jpg")
        debugInput.delete()
        debugFrame.delete()
        debugSwitch.writeText("enabled")
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
            var remainingChecks = 30
            while (debugFrame.length() == 0L && remainingChecks-- > 0) Thread.sleep(100)
            assertTrue("没有生成电脑端调试预览帧", debugFrame.length() > 0L)
            val preview = requireNotNull(BitmapFactory.decodeFile(debugFrame.path)) {
                "调试预览帧不是有效 JPEG"
            }
            assertTrue("调试预览帧尺寸无效", preview.width > 0 && preview.height > 0)
            preview.recycle()
        } finally {
            instrumentation.runOnMainSync {
                camera.stop()
                owner.registry.currentState = Lifecycle.State.DESTROYED
            }
            debugSwitch.delete()
            debugFrame.delete()
            debugInput.delete()
        }
    }
}
