plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.hilt")
}

android {
    namespace = "com.zkyjss.eyeproject.core.data"
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
