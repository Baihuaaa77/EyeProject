# 眼疲劳风险评分算法（纯标准库，无外部依赖）
cmake_minimum_required(VERSION 3.22)
project(eye_health CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# JNI shared library -> libeye_health_jni.so
add_library(eye_health_jni SHARED
    eye_health_jni.cpp
    model.cpp
)
target_include_directories(eye_health_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(eye_health_jni PRIVATE log)
