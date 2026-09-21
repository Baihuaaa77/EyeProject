package com.zkyjss.eyeproject

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zkyjss.eyeproject.feature.testmodel.gaze.GazeNativeModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 针对 [GazeNativeModel] 的 LiteRT Android API 路线（`infer_use_android_api`，
 * 即 `CompiledModel.create` + `createInputBuffers` + `run`）的仪器化测试。
 *
 * 运行前置条件：
 * 1. 真机（arm64-v8a 且支持 GPU delegate）；模拟器可能因无 GPU 而失败。
 * 2. 在 `app/src/androidTest/assets/` 放一张测试图片 `test_face.jpg`（人脸照片最佳）。
 *
 * 命令行运行（单测）：
 * ```
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.zkyjss.eyeproject.GazeNativeModelAndroidApiTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class GazeNativeModelAndroidApiTest {

    private lateinit var model: GazeNativeModel

    @Before
    fun setUp() {
        // 用「被测应用」的 context：l2cs_net.tflite 与 shaders 已合并进 app 的 assets。
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        model = GazeNativeModel(targetContext)
    }

    @After
    fun tearDown() {
        model.close()
    }

    @Test
    fun infer_use_android_api_returns_yaw_pitch() {
        val bitmap = loadTestImage()
        assertNotNull(
            "未找到测试图片：请将 test_face.jpg 放到 app/src/androidTest/assets/",
            bitmap
        )
        // 重复推理多次，覆盖 inputData / resizedBitmap / compiledModel 的复用路径。
        repeat(3) { iteration ->
            val result = model.infer_use_android_api(bitmap!!)

            assertEquals("第 $iteration 次：输出张量数应为 1", 1, result.outputs.size)
            val yawPitch = result.outputs[0]
            assertEquals("第 $iteration 次：[yaw, pitch] 应为 2 个值", 2, yawPitch.size)

            val yaw = yawPitch[0]
            val pitch = yawPitch[1]
            assertTrue("第 $iteration 次：yaw 应为有限值，实际 $yaw", yaw.isFinite())
            assertTrue("第 $iteration 次：pitch 应为有限值，实际 $pitch", pitch.isFinite())
            assertTrue("第 $iteration 次：耗时不应为负", result.latencyMs >= 0)
            assertTrue("第 $iteration 次：耗时异常（${result.latencyMs} ms）", result.latencyMs < 60_000)
        }
    }

    private fun loadTestImage(): Bitmap? {
        // 测试 APK 自己的 assets（src/androidTest/assets/）。
        val testContext = InstrumentationRegistry.getInstrumentation().context
        return testContext.assets.open(TEST_IMAGE).use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    companion object {
        private const val TEST_IMAGE = "sample1.png"
    }
}
