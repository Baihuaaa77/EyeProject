plugins {
    id("eyeproject.android.application")
    id("eyeproject.android.application.compose")
    id("eyeproject.android.hilt")
}

android {
    namespace = "com.zkyjss.eyeproject"

    // 本机 NDK 28 安装不完整，统一用完整的 27.3（r27d）
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.zkyjss.eyeproject"
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 正式包保持 ARM64；Debug 额外构建 x86_64 供模拟器直接运行。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 模拟器直接运行 x86_64 MediaPipe，避免 ARM64 原生库转译崩溃。
            ndk.abiFilters += "x86_64"
        }
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:notifications"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:camera"))
    implementation(project(":feature:fatigue"))
    implementation(project(":feature:testmodel"))
    implementation(project(":feature:services"))
    implementation(project(":feature:mine"))
    implementation(project(":feature:agent"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
