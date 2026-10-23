plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
}

android {
    namespace = "com.zkyjss.eyeproject.core.ui"
}

// 小瞳互动音与测试提示音同出一处（shared/feedback/res/raw，iOS 打包同一目录）
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addStaticSourceDirectory("../../shared/feedback/res")
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 全屏页（EyeFullScreenPage）设置状态栏图标深浅
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
