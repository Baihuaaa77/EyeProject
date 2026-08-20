#pragma once

#include <cstdint>

namespace gaze {

// Runs L2CS gaze estimation on an RGB image (raw pixels, no file IO).
//   rgb        : width*height*3 bytes, RGB order, values 0..255
//   width,height : image dimensions
//   model_path : path to l2cs_net.tflite
// Outputs yaw/pitch in degrees (bins-to-degrees conversion).
// Returns false on failure (model load / inference / bad args).
bool RunGaze(const uint8_t* rgb, int width, int height,
             const char* model_path, float* yaw, float* pitch);

}  // namespace gaze

