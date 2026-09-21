package com.zkyjss.eyeproject.feature.fatigue

/**
 * JNI 桥（重构版：时间序列输入）。
 * 对应 cpp/eye_health_jni.cpp 里的
 * Java_com_zkyjss_eyeproject_feature_fatigue_EyeHealthNative_*。
 *
 * 输入各数组等长；profile 见 [UserProfile.toDoubleArray]。
 * computeRiskSeries 返回 double[29]，computeRiskSeriesText 返回 String[9]。
 */
object EyeHealthNative {

    init {
        System.loadLibrary("eye_health_jni")
    }

    external fun computeRiskSeries(
        timestamps: LongArray,
        lux: FloatArray,
        brightness: IntArray,
        distance: FloatArray,
        duration: FloatArray,
        blink: IntArray,
        profile: DoubleArray
    ): DoubleArray

    external fun computeRiskSeriesText(
        timestamps: LongArray,
        lux: FloatArray,
        brightness: IntArray,
        distance: FloatArray,
        duration: FloatArray,
        blink: IntArray,
        profile: DoubleArray
    ): Array<String>

    /** 运行时切换为训练版逻辑回归组合层（β 由 tools/train_logistic.py 拟合）。 */
    external fun setCombinerLogistic(bias: Double, beta: DoubleArray)

    /** 恢复默认规则版组合层。 */
    external fun resetCombiner()
}
