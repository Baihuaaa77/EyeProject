plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
    id("eyeproject.android.hilt")
    id("eyeproject.kotlin.serialization")
}

android {
    namespace = "com.zkyjss.eyeproject.feature.services"
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addStaticSourceDirectory("../../shared/feedback/res")
    }
}

dependencies {
    implementation("com.zkyjss.eyeproject.shared:screening:1.0.0")
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:camera"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.vico.compose.m3)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
