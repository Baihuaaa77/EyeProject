import java.util.Properties

plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.hilt")
}

// 云端地址：-Peyeproject.apiBaseUrl=... 或根 local.properties；缺省为空 → App 只在本机保存，不联网。
val apiBaseUrl: String = providers.gradleProperty("eyeproject.apiBaseUrl").orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("eyeproject.apiBaseUrl") }
    ?: ""

android {
    namespace = "com.zkyjss.eyeproject.core.network"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.trimEnd('/')}\"")
    }
}

dependencies {
    api("com.zkyjss.eyeproject.shared:sync:1.0.0")
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
