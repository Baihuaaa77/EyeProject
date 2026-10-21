plugins { kotlin("multiplatform") }
kotlin {
    jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "EyeScreening"
            isStatic = true
            export(project(":model"))
            export(project(":domain"))
            // 云端同步契约与苹果端云端客户端（core.sync.client）
            export(project(":sync"))
        }
    }
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":model"))
            api(project(":domain"))
            api(project(":sync"))
            implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
