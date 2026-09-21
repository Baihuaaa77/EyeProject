// JNI bridge: Java_com_zkyjss_eyeproject_feature_testmodel_gaze_GazeNative_run
// 使用常驻 GazeEngine：首次调用 Init（EGL/shader/模型编译），之后每帧 Run 复用。
#include <jni.h>

#include <cstdint>
#include <mutex>

#include "gaze_engine.h"

namespace {
gaze::GazeEngine g_engine;
std::once_flag g_init_once;
std::mutex g_run_mutex;
bool g_init_ok = false;
}  // namespace

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_zkyjss_eyeproject_feature_testmodel_gaze_GazeNative_run(JNIEnv* env,
                                                            jobject /*thiz*/,
                                                            jbyteArray pixels,
                                                            jint width,
                                                            jint height,
                                                            jstring modelPath,
                                                            jstring shadersDir) {
  jfloatArray result = env->NewFloatArray(2);
  if (result == nullptr || pixels == nullptr || modelPath == nullptr ||
      shadersDir == nullptr) {
    return result;  // null -> Kotlin 侧判空
  }

  jbyte* buf = env->GetByteArrayElements(pixels, nullptr);
  const char* model = env->GetStringUTFChars(modelPath, nullptr);
  const char* shaders = env->GetStringUTFChars(shadersDir, nullptr);
  if (buf == nullptr || model == nullptr || shaders == nullptr) {
    if (buf != nullptr) env->ReleaseByteArrayElements(pixels, buf, JNI_ABORT);
    if (model != nullptr) env->ReleaseStringUTFChars(modelPath, model);
    if (shaders != nullptr) env->ReleaseStringUTFChars(shadersDir, shaders);
    return result;
  }

  // 首次调用初始化（模型/shaders 以首次传入为准）
  std::call_once(g_init_once, [&] { g_init_ok = g_engine.Init(model, shaders); });

  float yaw = 0.0f, pitch = 0.0f;
  bool ok = false;
  {
    std::lock_guard<std::mutex> lock(g_run_mutex);  // GL/模型非线程安全
    ok = g_engine.Run(reinterpret_cast<const uint8_t*>(buf), width, height,
                      &yaw, &pitch);
  }

  env->ReleaseByteArrayElements(pixels, buf, JNI_ABORT);
  env->ReleaseStringUTFChars(modelPath, model);
  env->ReleaseStringUTFChars(shadersDir, shaders);

  if (!ok) return result;  // 保持 null/全 0，Kotlin 判空
  float out[2] = {yaw, pitch};
  env->SetFloatArrayRegion(result, 0, 2, out);
  return result;
}

}  // extern "C"
