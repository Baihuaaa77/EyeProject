plugins {
    id("eyeproject.android.library")
}

android {
    namespace = "com.zkyjss.eyeproject.core.testing"
}

dependencies {
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
