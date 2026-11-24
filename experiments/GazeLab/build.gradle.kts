plugins {
    id("eyeproject.android.application")
    id("eyeproject.android.application.compose")
}

android {
    namespace = "com.zkyjss.gazelab"
    defaultConfig {
        applicationId = "com.zkyjss.gazelab"
        versionCode = 1
        versionName = "0.1.0"
        ndk.abiFilters += "arm64-v8a"
    }
    buildTypes {
        debug {
            ndk.abiFilters += "x86_64"
        }
    }
}

dependencies {
    implementation(project(":core:camera"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
}
