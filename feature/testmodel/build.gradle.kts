plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
    id("eyeproject.android.hilt")
}

android {
    namespace = "com.zkyjss.eyeproject.feature.testmodel"

    // 本机 NDK 28 安装不完整（缺 source.properties），改用完整的 27.3（r27d）
    ndkVersion = "27.3.13750724"

    defaultConfig {
        ndk {
            // LiteRT 预编译 .so 只提供 arm64-v8a
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))

    implementation("com.google.ai.edge.litert:litert:2.1.1")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.navigation.compose)

    // MediaPipe Tasks（Face Landmarker，478 点）
    implementation(libs.mediapipe.tasks.vision)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
