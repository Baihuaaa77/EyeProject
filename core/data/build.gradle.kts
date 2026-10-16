plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.hilt")
    id("eyeproject.kotlin.serialization")
}

android {
    namespace = "com.zkyjss.eyeproject.core.data"
    testOptions.unitTests.all {
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }
}

dependencies {
    api(project(":core:network"))
    // 本机记录列表的摘要解析（LocalRecordCatalog）与苹果端共用；纯 Kotlin 共享库
    api("com.zkyjss.eyeproject.shared:screening:1.0.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
