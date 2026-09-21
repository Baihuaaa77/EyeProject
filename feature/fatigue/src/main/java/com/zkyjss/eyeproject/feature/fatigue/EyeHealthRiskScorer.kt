package com.zkyjss.eyeproject.feature.fatigue

import com.zkyjss.eyeproject.core.model.MonitoringSample
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 眼疲劳风险评分门面：采样序列 + 画像 → JNI → [FatigueRisk]。
 * 组合层可在运行时切换（规则版 / 训练版逻辑回归）。
 */
@Singleton
class EyeHealthRiskScorer @Inject constructor() {

    @Volatile
    private var combinerName: String = "RuleBased"

    fun score(
        samples: List<MonitoringSample>,
        profile: UserProfile = UserProfile()
    ): FatigueRisk {
        val n = samples.size
        val ts = LongArray(n)
        val lux = FloatArray(n)
        val bri = IntArray(n)
        val dist = FloatArray(n)
        val dur = FloatArray(n)
        val blink = IntArray(n)
        samples.forEachIndexed { i, s ->
            ts[i] = s.timestampMillis
            lux[i] = s.ambientLux
            bri[i] = s.screenBrightness
            dist[i] = s.distanceCm
            dur[i] = s.durationMin
            blink[i] = s.blinkCount
        }
        val profileArr = profile.toDoubleArray()
        val numeric = EyeHealthNative.computeRiskSeries(ts, lux, bri, dist, dur, blink, profileArr)
        val text = EyeHealthNative.computeRiskSeriesText(ts, lux, bri, dist, dur, blink, profileArr)
        return FatigueRisk.fromOutput(numeric, text, combinerName)
    }

    /** 切换为训练版逻辑回归（β 由 tools/train_logistic.py 拟合）。 */
    fun useTrainedLogistic(bias: Double, beta: DoubleArray) {
        EyeHealthNative.setCombinerLogistic(bias, beta)
        combinerName = "TrainedLogistic"
    }

    fun useRuleBased() {
        EyeHealthNative.resetCombiner()
        combinerName = "RuleBased"
    }
}
