pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "eyeproject-server"
// 与安卓、iOS 共用同一份传输契约与记录摘要解析。
includeBuild("../../shared")
