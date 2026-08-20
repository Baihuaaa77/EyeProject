# LiteRT C++ SDK + L2CS-Net gaze 管线（JNI 桥）
cmake_minimum_required(VERSION 3.22)
project(gaze_native CXX)

# LiteRT C++ SDK 强制 C++20
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---------------------------------------------------------------------------
# LiteRT C++ SDK（litert_cc_api 静态库 + libLiteRt.so 运行时）
# ---------------------------------------------------------------------------
set(LITERT_CC_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/litert_cc_sdk")
add_compile_options(-Wno-deprecated-declarations)
add_subdirectory("${LITERT_CC_SDK_DIR}" "${CMAKE_BINARY_DIR}/litert_cc_sdk")
include_directories("${LITERT_CC_SDK_DIR}")
# SDK 通过 FetchContent 拉 OpenCL 头文件
include_directories("${CMAKE_BINARY_DIR}/opencl_headers")

# stb 头文件（third_party/stb/）
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# ---------------------------------------------------------------------------
# image_utils 静态库
# ---------------------------------------------------------------------------
add_library(image_utils STATIC
    image_utils.cc
)
target_include_directories(image_utils PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

# ---------------------------------------------------------------------------
# gaze_native 共享库（JNI 桥）
# ---------------------------------------------------------------------------
add_library(gaze_native SHARED
    gaze_engine.cpp
    jni_bridge.cpp
)
target_include_directories(gaze_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(gaze_native
    PRIVATE
      image_utils
      litert_cc_api
      absl::log_internal_check_op
      absl::log_internal_message
      absl::log_internal_log_sink_set
      absl::log
      absl::check
      absl::hash
      absl::raw_hash_set
      android
      log
)
