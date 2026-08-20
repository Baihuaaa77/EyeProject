// JNI bridge: Java_com_zkyjss_eyeproject_feature_testmodel_GazeNative_run
#include <jni.h>

#include <cstdint>

#include "gaze_engine.h"

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_zkyjss_eyeproject_feature_testmodel_GazeNative_run(JNIEnv* env, jobject /*thiz*/,
                                          jbyteArray pixels, jint width,
                                          jint height, jstring modelPath) {
  jfloatArray result = env->NewFloatArray(2);
  if (result == nullptr || pixels == nullptr || modelPath == nullptr) {
    return result;  // null -> Kotlin 侧判空
  }

  jbyte* buf = env->GetByteArrayElements(pixels, nullptr);
  const char* model = env->GetStringUTFChars(modelPath, nullptr);
  if (buf == nullptr || model == nullptr) {
    if (buf != nullptr) env->ReleaseByteArrayElements(pixels, buf, JNI_ABORT);
    if (model != nullptr) env->ReleaseStringUTFChars(modelPath, model);
    return result;
  }

  float yaw = 0.0f, pitch = 0.0f;
  gaze::RunGaze(reinterpret_cast<const uint8_t*>(buf), width, height, model,
                &yaw, &pitch);

  env->ReleaseByteArrayElements(pixels, buf, JNI_ABORT);
  env->ReleaseStringUTFChars(modelPath, model);

  float out[2] = {yaw, pitch};
  env->SetFloatArrayRegion(result, 0, 2, out);
  return result;
}

}  // extern "C"

