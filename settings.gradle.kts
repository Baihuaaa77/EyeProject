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

include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:notifications")
include(":core:testing")
include(":core:camera")
include(":feature:fatigue")
include(":feature:testmodel")
include(":feature:services")
include(":feature:mine")
include(":feature:agent")
