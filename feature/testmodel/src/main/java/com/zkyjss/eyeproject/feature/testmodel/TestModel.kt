package com.zkyjss.eyeproject.feature.testmodel

import android.graphics.Bitmap

/** 模型信息：名称 + 输入形状 + 输出形状列表。 */
data class ModelInfo(
    val name: String,
    val inputShape: IntArray,
    val outputShapes: List<IntArray>
)

/** 一次推理的结果：每个输出张量展平后的 float 数组 + 耗时。 */
data class InferenceResult(
    val outputs: List<FloatArray>,
    val latencyMs: Long
)

/** 可被测试页加载、推理的模型抽象（默认实现是 LiteRT 的 .tflite）。 */
interface TestModel {
    val info: ModelInfo
    fun infer_use_cpp_lib(bitmap: Bitmap): InferenceResult
    fun infer_use_android_api(bitmap: Bitmap): InferenceResult
    fun close()
}
