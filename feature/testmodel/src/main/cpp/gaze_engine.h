#pragma once

#include <cstdint>
#include <optional>
#include <vector>

#include "image_processor.h"
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_tensor_buffer.h"

namespace gaze {

// 常驻引擎：Init 一次（EGL/GL 上下文 + shader 编译 + 模型编译 + GL buffers），
// Run 每帧复用。App 实时推理必须用此类，避免每帧重建上下文/编译模型（几百 ms 级开销）。
class GazeEngine {
 public:
  GazeEngine() = default;
  ~GazeEngine();

  // 持有 GL/LiteRT 资源，禁止拷贝
  GazeEngine(const GazeEngine&) = delete;
  GazeEngine& operator=(const GazeEngine&) = delete;

  // 初始化（仅调用一次；失败返回 false）
  //   model_path : l2cs_net.tflite 路径
  //   shaders_dir: 含 6 个 .glsl/.vert 的目录（App 传 filesDir/shaders；空则相对路径 shaders/）
  bool Init(const char* model_path, const char* shaders_dir);

  // 每帧推理：RGB 像素 -> yaw/pitch（必须先 Init 成功）
  bool Run(const uint8_t* rgb, int width, int height, float* yaw, float* pitch);

  // 释放资源（析构自动调用）
  void Close();

  bool initialized() const { return initialized_; }

 private:
  bool initialized_ = false;
  ImageProcessor processor_;
  std::optional<litert::Environment> env_;  // Environment 无默认构造，用 optional
  litert::CompiledModel compiled_model_;    // 可默认构造
  std::vector<litert::TensorBuffer> input_buffers_;
  std::vector<litert::TensorBuffer> output_buffers_;
};

// 便捷单次调用（内部常驻引擎，首次自动 Init；模型/shaders 以首次传入为准）
bool RunGaze(const uint8_t* rgb, int width, int height, const char* model_path,
             const char* shaders_dir, float* yaw, float* pitch);

}  // namespace gaze

