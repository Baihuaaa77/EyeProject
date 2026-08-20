#include "gaze_engine.h"

#include <vector>

#include "absl/types/span.h"
#include "image_utils.h"
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_macros.h"

namespace gaze {
namespace {

// 90 angle bins -> degrees, same as Python: (p * arange(90)).sum() * 4 - 180
float BinsToDegrees(const std::vector<float>& bins) {
  float sum = 0.0f;
  for (size_t i = 0; i < bins.size(); ++i) {
    sum += bins[i] * static_cast<float>(i);
  }
  return sum * 4.0f - 180.0f;
}

}  // namespace

bool RunGaze(const uint8_t* rgb, int width, int height,
             const char* model_path, float* yaw, float* pitch) {
  if (rgb == nullptr || width <= 0 || height <= 0 || model_path == nullptr ||
      yaw == nullptr || pitch == nullptr) {
    return false;
  }

  // 1) Preprocess: resize 448x448, ImageNet mean/std, HWC -> CHW
  const int kOut = 448;
  std::vector<float> input =
      ImageUtils::PreprocessInputForGazeCpu(rgb, width, height, 3, kOut, kOut);

  // 2) LiteRT: env -> compile model for CPU
  LITERT_ASSIGN_OR_RETURN(auto env, litert::Environment::Create({}), false);
  LITERT_ASSIGN_OR_RETURN(
      auto compiled_model,
      litert::CompiledModel::Create(env, model_path,
                                    litert::HwAccelerators::kCpu),
      false);
  LITERT_ASSIGN_OR_RETURN(auto input_buffers,
                          compiled_model.CreateInputBuffers(), false);
  LITERT_ASSIGN_OR_RETURN(auto output_buffers,
                          compiled_model.CreateOutputBuffers(), false);

  // 3) Feed input + run
  LITERT_RETURN_IF_ERROR(
      input_buffers[0].Write(absl::MakeConstSpan(input)), false);
  LITERT_RETURN_IF_ERROR(compiled_model.Run(input_buffers, output_buffers),
                         false);

  // 4) Read outputs: yaw bins [1,90], pitch bins [1,90]
  std::vector<float> yaw_bins(90), pitch_bins(90);
  LITERT_RETURN_IF_ERROR(output_buffers[0].Read(absl::MakeSpan(yaw_bins)),
                         false);
  LITERT_RETURN_IF_ERROR(output_buffers[1].Read(absl::MakeSpan(pitch_bins)),
                         false);

  *yaw = BinsToDegrees(yaw_bins);
  *pitch = BinsToDegrees(pitch_bins);
  return true;
}

}  // namespace gaze

