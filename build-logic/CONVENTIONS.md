# build-logic

> Gradle 复合构建：约定插件（convention plugins）集中管理 SDK 版本与插件应用，模块 build 文件只保留差异项（对标 NIA）。

## 职责

- 统一 compileSdk / minSdk / targetSdk / Java 17 / NDK 相关配置
- 收敛插件组合，模块按需声明一行 id 即可

## 约定插件清单（convention/）

| 插件 id | 实现类 | 作用 |
|---|---|---|
| `eyeproject.android.application` | `AndroidApplicationConventionPlugin` | com.android.application + SDK 37 / min 26 / target 37 / Java 17 |
| `eyeproject.android.library` | `AndroidLibraryConventionPlugin` | com.android.library + SDK 37 / min 26 / Java 17 |
| `eyeproject.android.application.compose` | `AndroidApplicationComposeConventionPlugin` | Compose 编译器插件 + `buildFeatures.compose` |
| `eyeproject.android.library.compose` | `AndroidLibraryComposeConventionPlugin` | 同上（Library 版） |
| `eyeproject.android.hilt` | `AndroidHiltConventionPlugin` | Hilt + KSP + hilt-android/compiler 依赖 |
| `eyeproject.kotlin.serialization` | `AndroidKotlinSerializationConventionPlugin` | serialization 编译器插件（`@Serializable` 类型安全路由用） |

## 模块接入指南

```kotlin
// 普通 Android 库（model/domain/testing/common）
plugins { id("eyeproject.android.library") }

// Compose 库（designsystem/ui）
plugins { id("eyeproject.android.library"); id("eyeproject.android.library.compose") }

// 需要 Hilt（data/notifications/camera）
plugins { id("eyeproject.android.library"); id("eyeproject.android.hilt") }

// feature 模块标准组合
plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
    id("eyeproject.android.hilt")
    id("eyeproject.kotlin.serialization")
}

// app
plugins {
    id("eyeproject.android.application")
    id("eyeproject.android.application.compose")
    id("eyeproject.android.hilt")
}
```

## 依赖与版本

- 插件实现依赖在 `convention/build.gradle.kts` 硬编码：AGP 9.3.0、Kotlin 2.2.10（含 compose-compiler、kotlin-serialization）、Hilt 2.59.2、KSP 2.2.10-2.0.2
- ⚠️ 这些版本与主工程 `gradle/libs.versions.toml` 各维护一份，**升级时两处必须同步**（后续可让 build-logic 读 version catalog 消除双份）
- `build-logic/settings.gradle.kts`：独立构建，google + mavenCentral
- `build-logic/gradle.properties`：`kotlin.compiler.execution.strategy=in-process`（沙箱内避免 Kotlin 编译守护进程写 `~/.kotlin` 被拒）

## 注意事项

- 根 `build.gradle.kts` 保持空壳（仅注释），不要往里加配置——一切经约定插件
- 新增约定插件：在 `convention/build.gradle.kts` 的 `gradlePlugin.plugins` 注册 id + 实现类，并在本文档登记
