plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.hilt")
}

android {
    namespace = "com.zkyjss.eyeproject.core.notifications"
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
