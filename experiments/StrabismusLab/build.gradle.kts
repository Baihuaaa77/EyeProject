plugins {
    id("eyeproject.android.application")
    id("eyeproject.android.application.compose")
}

val labGitCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootProject.projectDir
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifBlank { "unknown" } }.orElse("unknown").get()

android {
    namespace = "com.zkyjss.strabismuslab"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.zkyjss.strabismuslab"
        versionCode = 2
        versionName = "0.9.5-lab"
        ndk.abiFilters += "arm64-v8a"
        buildConfigField("String", "LAB_GIT_COMMIT", "\"$labGitCommit\"")
    }
}

dependencies {
    implementation("com.zkyjss.eyeproject.shared:screening:1.0.0")
    implementation(project(":core:camera"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}

configurations.configureEach {
    // Lifecycle 2.8+ 已将 DefaultLifecycleObserver 合并进 lifecycle-common，排除空兼容壳避免离线版本分裂。
    exclude(group = "androidx.lifecycle", module = "lifecycle-common-java8")
}
