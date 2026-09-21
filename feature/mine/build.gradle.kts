plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
    id("eyeproject.kotlin.serialization")
}

android {
    namespace = "com.zkyjss.eyeproject.feature.mine"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
