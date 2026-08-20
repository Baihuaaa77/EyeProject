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
        // tasks-vision / LiteRT 预编译 .so 只提供 arm64-v8a
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
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
    implementation(project(":feature:camera"))
    implementation(project(":feature:home"))
    implementation(project(":feature:testmodel"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
