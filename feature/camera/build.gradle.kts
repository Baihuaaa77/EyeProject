plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.hilt")
}

android {
    namespace = "com.zkyjss.eyeproject.feature.camera"

    // 本机 NDK 28 安装不完整，统一用完整的 27.3（r27d）
    ndkVersion = "27.3.13750724"
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)

    // MediaPipe Tasks（Face Landmarker，478 点）
    implementation(libs.mediapipe.tasks.vision)
}
