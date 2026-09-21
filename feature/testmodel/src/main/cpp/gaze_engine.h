#pragma once

#include <cstdint>
#include <optional>
#include <vector>

#include "image_processor.h"
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_tensor_buffer.h"

namespace gaze {

class GazeEngine {
 public:
  GazeEngine() = default;
  ~GazeEngine();

  // 持有 GL/LiteRT 资源，禁止拷贝
  GazeEngine(const GazeEngine&) = delete;
  GazeEngine& operator=(const GazeEngine&) = delete;

  bool Init(const char* model_path, const char* shaders_dir);

  bool Run(const uint8_t* rgb, int width, int height, float* yaw, float* pitch);

  void Close();

  bool initialized() const { return initialized_; }

 private:
  bool initialized_ = false;
  ImageProcessor processor_;
  std::optional<litert::Environment> env_;
  litert::CompiledModel compiled_model_;
  std::vector<litert::TensorBuffer> input_buffers_;
  std::vector<litert::TensorBuffer> output_buffers_;
};

}