#include "gaze_engine.h"

#include <iostream>
#include <mutex>
#include <string>
#include <vector>

#include "absl/types/span.h"
#include "image_utils.h"
#include "litert/cc/litert_macros.h"

namespace gaze {

namespace {

litert::Options CreateGpuOptions(bool use_gl_buffers) {
  LITERT_ASSIGN_OR_ABORT(litert::Options options, litert::Options::Create());
  LITERT_ASSIGN_OR_ABORT(auto& gpu_options, options.GetGpuOptions());
  if (use_gl_buffers) {
    LITERT_ABORT_IF_ERROR(
        gpu_options.SetPrecision(litert::GpuOptions::Precision::kFp32));
    LITERT_ABORT_IF_ERROR(gpu_options.SetBufferStorageType(
        litert::GpuOptions::BufferStorageType::kBuffer));
    LITERT_ABORT_IF_ERROR(gpu_options.EnableExternalTensorsMode(true));
  } else {
    LITERT_ABORT_IF_ERROR(gpu_options.EnableExternalTensorsMode(false));
  }

  options.SetHardwareAccelerators(litert::HwAccelerators::kGpu |
                                  litert::HwAccelerators::kCpu);
  return options;
}

litert::Expected<std::vector<litert::TensorBuffer>> CreateGlInputBuffers(
    litert::Environment& env, litert::CompiledModel& compiled_model,
    int signature_index) {
  LITERT_ASSIGN_OR_RETURN(
      auto input_names, compiled_model.GetSignatureInputNames(signature_index));
  std::vector<litert::TensorBuffer> input_buffers;
  for (int i = 0; i < static_cast<int>(input_names.size()); ++i) {
    LITERT_ASSIGN_OR_RETURN(
        litert::TensorBufferRequirements input_buffer_requirements,
        compiled_model.GetInputBufferRequirements(signature_index, i));
    LITERT_ASSIGN_OR_RETURN(
        litert::RankedTensorType ranked_tensor_type,
        compiled_model.GetInputTensorType(signature_index, i));
    LITERT_ASSIGN_OR_RETURN(size_t buffer_size,
                            input_buffer_requirements.BufferSize());

    LITERT_ASSIGN_OR_RETURN(auto input_buffer,
                            litert::TensorBuffer::CreateManaged(
                                env, litert::TensorBufferType::kGlBuffer,
                                ranked_tensor_type, buffer_size));

    input_buffers.push_back(std::move(input_buffer));
  }
  return input_buffers;
}

litert::Expected<std::vector<litert::TensorBuffer>> CreateGlOutputBuffers(
    litert::Environment& env, litert::CompiledModel& compiled_model,
    int signature_index) {
  LITERT_ASSIGN_OR_RETURN(
      auto output_names,
      compiled_model.GetSignatureOutputNames(signature_index));
  std::vector<litert::TensorBuffer> output_buffers;
  output_buffers.reserve(output_names.size());
  for (int i = 0; i < static_cast<int>(output_names.size()); ++i) {
    LITERT_ASSIGN_OR_RETURN(
        litert::TensorBufferRequirements output_buffer_requirements,
        compiled_model.GetOutputBufferRequirements(signature_index, i));
    LITERT_ASSIGN_OR_RETURN(
        litert::RankedTensorType ranked_tensor_type,
        compiled_model.GetOutputTensorType(signature_index, i));
    LITERT_ASSIGN_OR_RETURN(size_t buffer_size,
                            output_buffer_requirements.BufferSize());

    LITERT_ASSIGN_OR_RETURN(auto output_buffer,
                            litert::TensorBuffer::CreateManaged(
                                env, litert::TensorBufferType::kGlBuffer,
                                ranked_tensor_type, buffer_size));

    output_buffers.push_back(std::move(output_buffer));
  }
  return output_buffers;
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

  // App 里相对路径不可用（cwd 不是 shader 所在处），shaders_dir 由 Kotlin 传入 filesDir/shaders
  const std::string sd = (shaders_dir != nullptr && *shaders_dir != '\0')
                             ? shaders_dir
                             : "shaders";  // 独立二进制时保持相对路径

  // 直接初始化成员（ImageProcessor 持 GL 句柄，禁止拷贝）
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
  env_ = std::move(*env_result);  // 必须持有 env，模型句柄依赖它

  litert::Options options = CreateGpuOptions(true);
  auto compiled_result =
      litert::CompiledModel::Create(*env_, model_path, options);
  if (!compiled_result) {
    std::cerr << "GazeEngine::Init: CompiledModel::Create failed." << std::endl;
    return false;
  }
  compiled_model_ = std::move(*compiled_result);

  auto in = CreateGlInputBuffers(*env_, compiled_model_, 0);
  if (!in) {
    std::cerr << "GazeEngine::Init: input buffers failed." << std::endl;
    return false;
  }
  input_buffers_ = std::move(*in);

  auto out = CreateGlOutputBuffers(*env_, compiled_model_, 0);
  if (!out) {
    std::cerr << "GazeEngine::Init: output buffers failed." << std::endl;
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

  GLuint tex_id_orig = processor_.CreateOpenGLTexture(rgb, width, height, 3);
  if (!tex_id_orig) {
    std::cerr << "Failed to create OpenGL texture for image" << std::endl;
    return false;
  }

  // Preprocess -> 448x448x3 NCHW float（直接写入 LiteRT GPU 输入缓冲，零拷贝）
  const int kOut = 448;
  const int num_channels = 3;
  GLuint preprocessed_buffer_id;
  LITERT_ASSIGN_OR_RETURN(auto gl_buffer, input_buffers_[0].GetGlBuffer(),
                         false);
  preprocessed_buffer_id = gl_buffer.id;

  if (!processor_.PreprocessInputForL2CS_NET(
          tex_id_orig, width, height, kOut, kOut, preprocessed_buffer_id,
          num_channels)) {
    std::cerr << "Failed to preprocess input image for gaze." << std::endl;
    processor_.DeleteOpenGLTexture(tex_id_orig);
    return false;
  }
  processor_.DeleteOpenGLTexture(tex_id_orig);  // 预处理结束，释放纹理

  LITERT_RETURN_IF_ERROR(compiled_model_.Run(input_buffers_, output_buffers_),
                         false);

  // Outputs: yaw bins [1,90], pitch bins [1,90]
  std::vector<float> yaw_bins(90), pitch_bins(90);
  LITERT_RETURN_IF_ERROR(output_buffers_[0].Read(absl::MakeSpan(yaw_bins)),
                         false);
  LITERT_RETURN_IF_ERROR(output_buffers_[1].Read(absl::MakeSpan(pitch_bins)),
                         false);

  *yaw = BinsToDegrees(yaw_bins);
  *pitch = BinsToDegrees(pitch_bins);
  return true;
}

void GazeEngine::Close() {
  if (!initialized_) return;
  input_buffers_.clear();
  output_buffers_.clear();
  compiled_model_ = litert::CompiledModel();
  env_.reset();
  // processor_ 的 GL 资源由 ImageProcessor 析构自动清理
  initialized_ = false;
}

// 便捷单次调用：静态常驻引擎 + 首次自动 Init（模型/shaders 以首次传入为准）
bool RunGaze(const uint8_t* rgb, int width, int height, const char* model_path,
             const char* shaders_dir, float* yaw, float* pitch) {
  static GazeEngine engine;
  static std::once_flag init_flag;
  static std::mutex run_mutex;  // GL/模型非线程安全
  static bool init_ok = false;
  std::call_once(init_flag, [&] { init_ok = engine.Init(model_path, shaders_dir); });
  if (!init_ok) return false;
  std::lock_guard<std::mutex> lock(run_mutex);
  return engine.Run(rgb, width, height, yaw, pitch);
}

}  // namespace gaze

