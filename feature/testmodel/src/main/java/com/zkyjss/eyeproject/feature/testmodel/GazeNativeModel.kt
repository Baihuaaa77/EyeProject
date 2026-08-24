package com.zkyjss.eyeproject.feature.testmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import androidx.core.graphics.get

/**
 * 基于 LiteRT C++ SDK（JNI）的注视估计模型。
 *
 * 数据流：Bitmap → RGB ByteArray → JNI run() → C++ gaze_engine（预处理 ImageNet 归一化 + 推理 + bins→角度）
 * → FloatArray[2] = [yaw, pitch]（度）。
 *
 * 归一化已在 C++ 端完成（PreprocessInputForGazeCpu：resize 448 + ImageNet mean/std + CHW），
 * 所以输出是已解码的角度，不再是 90-bin logits。
 */
class GazeNativeModel(context: Context) : TestModel {

    private val modelPath: String = copyAssetToFilesDir(context, MODEL_ASSET)
    private val shadersDir: String = copyShadersDir(context)

    // Android API 路线：模型只加载一次，避免每次推理重新 create（性能瓶颈）。
    // analyze 单线程串行，复用 compiledModel/buffers 是安全的。
    private val compiledModelLazy = lazy {
        CompiledModel.create(modelPath, CompiledModel.Options(Accelerator.GPU))
    }
    private val compiledModel by compiledModelLazy
    private val inputBuffers by lazy { compiledModel.createInputBuffers() }
    private val outputBuffers by lazy { compiledModel.createOutputBuffers() }

    // 复用大对象，避免每帧分配触发 GC（卡顿根源）
    private val inputData = FloatArray(1 * 3 * 448 * 448)
    private val resizedBitmap = Bitmap.createBitmap(448, 448, Bitmap.Config.ARGB_8888)

    override val info = ModelInfo(
        name = "L2CS-Net",
        inputShape = intArrayOf(1, 3, 448, 448),
        outputShapes = listOf(intArrayOf(2))  // [yaw, pitch]
    )

    override fun infer_use_cpp_lib(bitmap: Bitmap): InferenceResult {
        val start = SystemClock.elapsedRealtime()
        val (pixels, w, h) = bitmapToRgb(bitmap)
        val yawPitch = GazeNative.run(pixels, w, h, modelPath, shadersDir)
        val latency = SystemClock.elapsedRealtime() - start
        return InferenceResult(
            outputs = listOf(yawPitch),
            latencyMs = latency
        )
    }

    override fun infer_use_android_api(bitmap: Bitmap): InferenceResult {
        val start = SystemClock.elapsedRealtime()

        // 模型输入尺寸
        val modelWidth = 448
        val modelHeight = 448

        // 图片缩放到模型尺寸（复用缓存 Bitmap，避免每帧分配 800KB）
        Canvas(resizedBitmap).drawBitmap(
            bitmap, null,
            Rect(0, 0, modelWidth, modelHeight), null
        )

        // 填充输入张量（复用 inputData，避免每帧分配 2.4MB）
        for (y in 0 until modelHeight ) {
            for (x in 0 until modelWidth) {
                val pixel = resizedBitmap[x, y]

                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f

                inputData[y * modelWidth + x] = (r - MEAN[0]) / STD[0]
                inputData[1 * modelHeight * modelWidth + y * modelHeight + x] = (g - MEAN[1]) / STD[1]
                inputData[2 * modelHeight * modelWidth + y * modelHeight + x] = (b - MEAN[2]) / STD[2]
            }
        }

        // Fill the input buffer
        inputBuffers.get(0).writeFloat(inputData)

        // Invoke
        compiledModel.run(inputBuffers, outputBuffers)

        // Read the output
        val logits_yaw = outputBuffers.get(0).readFloat()
        val logits_pitch = outputBuffers.get(1).readFloat()

        val yawDeg = binsToDegrees(logits_yaw)
        val pitchDeg = binsToDegrees(logits_pitch)

        val latency = SystemClock.elapsedRealtime() - start

        return InferenceResult(
            outputs = listOf(floatArrayOf(yawDeg, pitchDeg)),
            latencyMs = latency
        )
    }

    override fun close() {
        // 释放 Android API 路线的模型（如果已加载）
        if (compiledModelLazy.isInitialized()) {
            compiledModel.close()
        }
    }

    private fun bitmapToRgb(bitmap: Bitmap): Triple<ByteArray, Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[i * 3] = ((p shr 16) and 0xff).toByte()      // R
            rgb[i * 3 + 1] = ((p shr 8) and 0xff).toByte()   // G
            rgb[i * 3 + 2] = (p and 0xff).toByte()           // B
        }
        return Triple(rgb, w, h)
    }

    /** 把 assets/shaders 下 6 个 shader 拷贝到 filesDir/shaders，返回目录路径（C++ 需要真实路径）。 */
    private fun copyShadersDir(context: Context): String {
        val dir = File(context.filesDir, "shaders")
        if (!dir.exists()) {
            context.assets.list("shaders")?.forEach { name ->
                context.assets.open("shaders/$name").use { input ->
                    File(dir, name).apply { parentFile?.mkdirs() }
                        .outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return dir.absolutePath
    }

    private fun copyAssetToFilesDir(context: Context, assetName: String): String {
        val dest = File(context.filesDir, assetName)
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest.absolutePath
    }

    private fun binsToDegrees(bins : FloatArray): Float {
        var sum = 0.0f
        for (i in 0 until 90) {
            sum += i * bins[i]
        }

        return sum * 4 - 180
    }

    companion object {
        private const val MODEL_ASSET = "l2cs_net.tflite"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
