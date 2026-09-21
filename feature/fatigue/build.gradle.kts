plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
    id("eyeproject.android.hilt")
    id("eyeproject.kotlin.serialization")
}

android {
    namespace = "com.zkyjss.eyeproject.feature.fatigue"

    // 本机 NDK 28 安装不完整（缺 source.properties），改用完整的 27.3（r27d）
    ndkVersion = "27.3.13750724"

    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
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
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:camera"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.vico.compose.m3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
