pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EyeProject"
includeBuild("build-logic")
// 共享构建独立管理 Kotlin/Native 工具链；JVM API 保持与现有安卓 Kotlin 2.2 兼容。
includeBuild("shared")

include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:notifications")
include(":core:camera")
include(":feature:home")
include(":feature:testmodel")
include(":feature:services")
include(":feature:mine")
include(":feature:agent")
