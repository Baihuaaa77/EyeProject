plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.zkyjss.eyeproject"
version = "0.2.0"

val ktor = "3.6.0"

// 不用工具链下载：以本机 JDK 编译，字节码目标 17（与 shared 一致）。
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

application { mainClass.set("com.zkyjss.eyeproject.server.ApplicationKt") }

dependencies {
    implementation("com.zkyjss.eyeproject.shared:sync:1.0.0")
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-rate-limit:$ktor")
    implementation("io.ktor:ktor-server-forwarded-header:$ktor")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:13.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.10.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.h2database:h2:2.5.252")
    implementation("ch.qos.logback:logback-classic:1.6.5")
    // 调大模型：一个解析地址连不上时自动换下一个（JDK HttpClient 只连第一个地址）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor")
}

tasks.test { useJUnitPlatform() }
