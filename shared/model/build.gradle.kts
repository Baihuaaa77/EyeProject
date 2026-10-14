plugins { kotlin("multiplatform") }
kotlin {
    jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
