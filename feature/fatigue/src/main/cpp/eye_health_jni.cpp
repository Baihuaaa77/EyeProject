// JNI bridge for EyeHealthRiskScorer（重构版：时间序列输入，6 因子）。
//
// Kotlin 侧（包/类按需调整，示例见 kotlin-reference/EyeHealthNative.kt）：
//   object EyeHealthNative {
//     init { System.loadLibrary("eye_health_jni") }
//     external fun computeRiskSeries(
//         timestamps: LongArray, lux: FloatArray, brightness: IntArray,
//         distance: FloatArray, duration: FloatArray, blink: IntArray,
//         profile: DoubleArray): DoubleArray
//     external fun computeRiskSeriesText(...): Array<String>
//     external fun setCombinerLogistic(bias: Double, beta: DoubleArray)
//     external fun resetCombiner()
//   }
//
// 输入（各数组等长，按时间戳排序或乱序均可）：
//   timestamps 采样时间戳（毫秒）
//   lux        环境光照（lux）
//   brightness 屏幕亮度（0~100）
//   distance   用眼距离（cm；0 = 未检测到人脸/非用眼）
//   duration   连续用眼时长（分钟；算法可从时间戳重新推导，仅单样本时兜底用）
//   blink      本采样区间（默认 30 秒）内眨眼次数
//   profile    double[7]：age, hasMyopia, hasDryEye, parentalMyopia,
//              readingPosture, discomfortScore, contentContrast
//
// 输出 double[27]（固定顺序）：
//   0 totalRisk, 1 riskLevelCode(0/1/2), 2 linearRaw,
//   3 light, 4 distance, 5 time, 6 blink, 7 personal, 8 feedback,
//   9 continuousDurationMin, 10 cumulativeDurationMin, 11 blinkRatePerMin,
//   12 meanDistanceCm, 13 meanAmbientLux, 14 meanScreenBrightness,
//   15 sampleCount, 16 episodeCount,
//   17..26 特征向量(10)：light,distance,time,blink,personal,feedback,
//          nearWork,dryEye,glare,suscept
//
// 输出 String[8]（固定顺序）：
//   0 riskLevelLabel, 1 overallAdvice,
//   2 lightAdvice, 3 distanceAdvice, 4 timeAdvice,
//   5 blinkAdvice, 6 personalAdvice, 7 feedbackAdvice

#include <jni.h>

#include <mutex>
#include <vector>

#include "model.h"

namespace {

constexpr int kProfileLen = 7;
constexpr int kOutNumericLen = 27;
constexpr int kOutTextLen = 8;

std::mutex g_mutex;
eyehealth::EyeHealthRiskScorer g_scorer;

// 读取 profile double[7] → Profile
eyehealth::Profile readProfile(const double* p, int n) {
  eyehealth::Profile pr;
  if (p == nullptr || n < kProfileLen) return pr;
  pr.age = p[0];
  pr.hasMyopia = p[1];
  pr.hasDryEye = p[2];
  pr.parentalMyopia = p[3];
  pr.readingPosture = p[4];
  pr.discomfortScore = p[5];
  pr.contentContrast = p[6];
  return pr;
}

// 并行数组 → std::vector<Sample>
std::vector<eyehealth::Sample> readSamples(JNIEnv* env, jlongArray ts,
                                           jfloatArray lux, jintArray brightness,
                                           jfloatArray distance,
                                           jfloatArray duration,
                                           jintArray blink) {
  std::vector<eyehealth::Sample> out;
  if (ts == nullptr) return out;

  const jsize n = env->GetArrayLength(ts);
  if (n <= 0) return out;

  std::vector<jlong> t(n);
  std::vector<jfloat> l(n), d(n), du(n);
  std::vector<jint> b(n), bl(n);
  env->GetLongArrayRegion(ts, 0, n, t.data());
  if (lux) env->GetFloatArrayRegion(lux, 0, n, l.data());
  if (brightness) env->GetIntArrayRegion(brightness, 0, n, b.data());
  if (distance) env->GetFloatArrayRegion(distance, 0, n, d.data());
  if (duration) env->GetFloatArrayRegion(duration, 0, n, du.data());
  if (blink) env->GetIntArrayRegion(blink, 0, n, bl.data());

  out.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    eyehealth::Sample s;
    s.timestampMillis = static_cast<long long>(t[i]);
    s.ambientLux = static_cast<float>(l[i]);
    s.screenBrightness = static_cast<int>(b[i]);
    s.distanceCm = static_cast<float>(d[i]);
    s.durationMin = static_cast<float>(du[i]);
    s.blinkCount = static_cast<int>(bl[i]);
    out.push_back(s);
  }
  return out;
}

}  // namespace

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_zkyjss_eyeproject_feature_fatigue_EyeHealthNative_computeRiskSeries(
    JNIEnv* env, jobject /*thiz*/, jlongArray timestamps, jfloatArray lux,
    jintArray brightness, jfloatArray distance, jfloatArray duration,
    jintArray blink, jdoubleArray profile) {
  std::lock_guard<std::mutex> lock(g_mutex);

  const auto samples =
      readSamples(env, timestamps, lux, brightness, distance, duration, blink);

  std::vector<double> pbuf(kProfileLen, 0.0);
  if (profile) {
    const jsize pn = env->GetArrayLength(profile);
    const jsize len = pn < kProfileLen ? pn : kProfileLen;
    env->GetDoubleArrayRegion(profile, 0, len, pbuf.data());
  }
  const eyehealth::Profile pr = readProfile(pbuf.data(), kProfileLen);

  const eyehealth::RiskResult r = g_scorer.compute(samples, pr);

  const double out[kOutNumericLen] = {
      r.totalRisk,        static_cast<double>(r.riskLevel), r.linearRaw,
      r.light.value,      r.distance.value,  r.time.value,
      r.blink.value,      r.personal.value,  r.feedback.value,
      r.continuousDurationMin, r.cumulativeDurationMin, r.blinkRatePerMin,
      r.meanDistanceCm,  r.meanAmbientLux,  r.meanScreenBrightness,
      static_cast<double>(r.sampleCount),
      static_cast<double>(r.episodeCount),
      r.features[0],  r.features[1],  r.features[2],  r.features[3],
      r.features[4],  r.features[5],  r.features[6],  r.features[7],
      r.features[8],  r.features[9]};

  jdoubleArray result = env->NewDoubleArray(kOutNumericLen);
  if (result == nullptr) return nullptr;
  env->SetDoubleArrayRegion(result, 0, kOutNumericLen, out);
  return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_zkyjss_eyeproject_feature_fatigue_EyeHealthNative_computeRiskSeriesText(
    JNIEnv* env, jobject /*thiz*/, jlongArray timestamps, jfloatArray lux,
    jintArray brightness, jfloatArray distance, jfloatArray duration,
    jintArray blink, jdoubleArray profile) {
  std::lock_guard<std::mutex> lock(g_mutex);

  const auto samples =
      readSamples(env, timestamps, lux, brightness, distance, duration, blink);

  std::vector<double> pbuf(kProfileLen, 0.0);
  if (profile) {
    const jsize pn = env->GetArrayLength(profile);
    const jsize len = pn < kProfileLen ? pn : kProfileLen;
    env->GetDoubleArrayRegion(profile, 0, len, pbuf.data());
  }
  const eyehealth::Profile pr = readProfile(pbuf.data(), kProfileLen);

  const eyehealth::RiskResult r = g_scorer.compute(samples, pr);

  const char* texts[kOutTextLen] = {
      r.riskLevelLabel.c_str(), r.advice.c_str(),
      r.light.advice.c_str(),   r.distance.advice.c_str(),
      r.time.advice.c_str(),    r.blink.advice.c_str(),
      r.personal.advice.c_str(), r.feedback.advice.c_str()};

  jclass stringClass = env->FindClass("java/lang/String");
  if (stringClass == nullptr) return nullptr;
  jobjectArray arr = env->NewObjectArray(kOutTextLen, stringClass, nullptr);
  if (arr == nullptr) return nullptr;
  for (int i = 0; i < kOutTextLen; ++i) {
    jstring js = env->NewStringUTF(texts[i]);
    env->SetObjectArrayElement(arr, i, js);
    env->DeleteLocalRef(js);
  }
  return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zkyjss_eyeproject_feature_fatigue_EyeHealthNative_setCombinerLogistic(
    JNIEnv* env, jobject /*thiz*/, jdouble bias, jdoubleArray beta) {
  std::lock_guard<std::mutex> lock(g_mutex);

  eyehealth::LogisticParams lp;
  lp.bias = bias;
  if (beta != nullptr) {
    const jsize n = env->GetArrayLength(beta);
    const jsize len = n < 10 ? n : 10;
    std::vector<double> b(len, 0.0);
    env->GetDoubleArrayRegion(beta, 0, len, b.data());
    for (jsize i = 0; i < len; ++i) lp.beta[i] = b[i];
  }
  g_scorer.setCombiner(
      std::make_shared<eyehealth::TrainedLogisticCombiner>(lp));
}

extern "C" JNIEXPORT void JNICALL
Java_com_zkyjss_eyeproject_feature_fatigue_EyeHealthNative_resetCombiner(
    JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_scorer.setCombiner(std::make_shared<eyehealth::RuleBasedCombiner>());
}
