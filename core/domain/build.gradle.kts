plugins {
    id("eyeproject.android.library")
}

android {
    namespace = "com.zkyjss.eyeproject.core.domain"
}

dependencies {
    implementation(project(":core:model"))
}
