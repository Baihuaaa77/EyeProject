#include "gaze_engine.h"

#include <android/log.h>
#include <iostream>
#include <mutex>
#include <string>
#include <vector>

#include "absl/types/span.h"
#include "image_utils.h"
#include "litert/cc/litert_macros.h"
#include "litert/cc/options/litert_gpu_options.h"

#define GAZE_LOG(...) __android_log_print(ANDROID_LOG_INFO, "GazeEngine", __VA_ARGS__)

namespace gaze {

namespace {

// 设备实测：GL 外部张量零拷贝（RunAsync）在此设备报 "Output buffers cannot have events attached"，
// 因此走官方 main_cpu.cc 的 CPU 缓冲 + Write 路线；加速器仍 kGpu|kCpu（推理尽量 GPU，输入走 Write）。
litert::Options CreateGpuOptions() {
  LITERT_ASSIGN_OR_ABORT(litert::Options options, litert::Options::Create());
  LITERT_ASSIGN_OR_ABORT(auto& gpu_options, options.GetGpuOptions());
  LITERT_ABORT_IF_ERROR(gpu_options.EnableExternalTensorsMode(false));
  options.SetHardwareAccelerators(litert::HwAccelerators::kGpu |
                                  litert::HwAccelerators::kCpu);
  return options;
}

// 90 angle bins -> degrees, same as Python: (p * arange(90)).sum() * 4 - 180
float BinsToDegrees(const std::vector<float>& bins) {
  float sum = 0.0f;
  for (size_t i = 0; i < bins.size(); ++i) {
    sum += bins[i] * static_cast<float>(i);
  }
  return sum * 4.0f - 180.0f;
}

}  // namespace

GazeEngine::~GazeEngine() { Close(); }

bool GazeEngine::Init(const char* model_path, const char* shaders_dir) {
  if (initialized_) return true;
  if (model_path == nullptr || *model_path == '\0') return false;

  const std::string sd = (shaders_dir != nullptr && *shaders_dir != '\0')
                             ? shaders_dir
                             : "shaders";

  if (!processor_.InitializeGL(
          sd + "/passthrough_shader.vert", sd + "/mask_blend_compute.glsl",
          sd + "/resize_compute.glsl", sd + "/preprocess_compute.glsl",
          sd + "/deinterleave_masks.glsl", sd + "/l2cs_net_pre.glsl")) {
    std::cerr << "GazeEngine::Init: InitializeGL failed." << std::endl;
    return false;
  }

  auto env_result = litert::Environment::Create({});
  if (!env_result) {
    std::cerr << "GazeEngine::Init: Environment::Create failed." << std::endl;
    return false;
  }
  env_ = std::move(*env_result);

  litert::Options options = CreateGpuOptions();
  auto compiled_result =
      litert::CompiledModel::Create(*env_, model_path, options);
  if (!compiled_result) {
    std::cerr << "GazeEngine::Init: CompiledModel::Create failed." << std::endl;
    return false;
  }
  compiled_model_ = std::move(*compiled_result);

  // 官方 main_cpu.cc：默认（CPU 管理）缓冲，输入走 Write()
  auto in = compiled_model_.CreateInputBuffers();
  if (!in) {
    std::cerr << "GazeEngine::Init: CreateInputBuffers failed." << std::endl;
    return false;
  }
  input_buffers_ = std::move(*in);

  auto out = compiled_model_.CreateOutputBuffers();
  if (!out) {
    std::cerr << "GazeEngine::Init: CreateOutputBuffers failed." << std::endl;
    return false;
  }
  output_buffers_ = std::move(*out);

  initialized_ = true;
  return true;
}

bool GazeEngine::Run(const uint8_t* rgb, int width, int height,
                     float* yaw, float* pitch) {
  if (!initialized_) {
    std::cerr << "GazeEngine::Run: not initialized (call Init first)."
              << std::endl;
    return false;
  }
  if (rgb == nullptr || width <= 0 || height <= 0 || yaw == nullptr ||
      pitch == nullptr) {
    return false;
  }

  // 1) 上传纹理（官方 main_cpu.cc 同款）
  GLuint tex_id = processor_.CreateOpenGLTexture(rgb, width, height, 3);
  if (!tex_id) {
    std::cerr << "Failed to create OpenGL texture for image" << std::endl;
    return false;
  }

  // 2) GL 预处理写入普通 SSBO
  const int kOut = 448;
  const int num_channels = 3;
  const size_t input_elems = kOut * kOut * num_channels;
  GLuint ssbo = processor_.CreateOpenGLBuffer(nullptr,
                                              input_elems * sizeof(float));
  if (!processor_.PreprocessInputForL2CS_NET(
          tex_id, width, height, kOut, kOut, ssbo, num_channels)) {
    std::cerr << "Failed to preprocess input image for gaze." << std::endl;
    processor_.DeleteOpenGLBuffer(ssbo);
    processor_.DeleteOpenGLTexture(tex_id);
    return false;
  }
  processor_.DeleteOpenGLTexture(tex_id);

  // 3) 读回 CPU（官方 ReadBufferData 流程）
  std::vector<float> input(input_elems);
  if (!processor_.ReadBufferData(ssbo, 0, input.size() * sizeof(float),
                                input.data())) {
    std::cerr << "Failed to read preprocessed SSBO." << std::endl;
    processor_.DeleteOpenGLBuffer(ssbo);
    return false;
  }
  processor_.DeleteOpenGLBuffer(ssbo);

  // ---- DEBUG：确认输入是真实图像数据（非 0/恒定）----
  {
    float s = 0; for (float v : input) s += v;
    GAZE_LOG("DEBUG input sum=%.2f first=%.4f %.4f %.4f", s, input[0],
             input[1], input[2]);
  }

  // 4) Write + Run（官方 main_cpu.cc 喂数据方式）
  LITERT_RETURN_IF_ERROR(
      input_buffers_[0].Write(absl::MakeConstSpan(input)), false);
  LITERT_RETURN_IF_ERROR(compiled_model_.Run(input_buffers_, output_buffers_),
                         false);

  // 5) 读输出
  std::vector<float> yaw_bins(90), pitch_bins(90);
  LITERT_RETURN_IF_ERROR(output_buffers_[0].Read(absl::MakeSpan(yaw_bins)),
                         false);
  LITERT_RETURN_IF_ERROR(output_buffers_[1].Read(absl::MakeSpan(pitch_bins)),
                         false);

  // ---- DEBUG：原始 bins（恒定 = 模型输出没变化）----
  {
    float sy = 0, sp = 0;
    for (float v : yaw_bins) sy += v;
    for (float v : pitch_bins) sp += v;
    GAZE_LOG("DEBUG yaw_bins[0..3]=%.4f %.4f %.4f %.4f sum=%.2f | pitch_bins[0..3]=%.4f %.4f %.4f %.4f sum=%.2f",
             yaw_bins[0], yaw_bins[1], yaw_bins[2], yaw_bins[3], sy,
             pitch_bins[0], pitch_bins[1], pitch_bins[2], pitch_bins[3], sp);
  }

  *yaw = BinsToDegrees(yaw_bins);
  *pitch = BinsToDegrees(pitch_bins);
  GAZE_LOG("yaw=%.2f pitch=%.2f", *yaw, *pitch);
  return true;
}

void GazeEngine::Close() {
  if (!initialized_) return;
  input_buffers_.clear();
  output_buffers_.clear();
  compiled_model_ = litert::CompiledModel();
  env_.reset();
  initialized_ = false;
}

// 便捷单次调用：静态常驻引擎 + 首次自动 Init（模型/shaders 以首次传入为准）
bool RunGaze(const uint8_t* rgb, int width, int height, const char* model_path,
             const char* shaders_dir, float* yaw, float* pitch) {
  static GazeEngine engine;
  static std::once_flag init_flag;
  static std::mutex run_mutex;
  static bool init_ok = false;
  std::call_once(init_flag, [&] { init_ok = engine.Init(model_path, shaders_dir); });
  if (!init_ok) return false;
  std::lock_guard<std::mutex> lock(run_mutex);
  return engine.Run(rgb, width, height, yaw, pitch);
}

}  // namespace gaze

