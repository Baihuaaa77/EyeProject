---
name: new-module
description: 在 EyeProject 新增 Gradle 模块时使用。按 NIA 规范生成模块目录、build.gradle.kts（含插件组合决策）、settings.gradle.kts 注册与模块文档骨架。参数：<模块路径>，如 core:network 或 feature:vision
argument-hint: <模块路径>（如 core:network 或 feature:vision）
---

# 新增模块脚手架

参数：`<模块路径>`（如 `core:network`、`feature:vision`）。缺省时先问用户模块路径与用途。

## 步骤

1. **先判断类型与插件组合**（决策表来自 `build-logic/CONVENTIONS.md`）：

   | 模块类型 | 插件组合 |
   |---|---|
   | 纯逻辑库（model / domain / common / testing） | `eyeproject.android.library` |
   | Compose 组件库（designsystem / ui） | `android.library` + `android.library.compose` |
   | 需要 Hilt（data / notifications / camera） | `android.library` + `android.hilt` |
   | feature 模块 | `android.library` + `android.library.compose` + `android.hilt` + `eyeproject.kotlin.serialization` |

   模块一次性专属配置**直接写在模块 build 文件里**，不要为它新建约定插件（NIA 约定插件哲学）。

2. **建目录与 build.gradle.kts**（模板见同类型现有模块，如 `core/notifications/build.gradle.kts`）：

   ```kotlin
   plugins {
       id("eyeproject.android.library")
       // …按决策表追加
   }

   android {
       namespace = "com.zkyjss.eyeproject.<路径点号化>"
   }

   dependencies {
       implementation(project(":core:model"))  // 按需，注意依赖规则
   }
   ```

3. **注册** `settings.gradle.kts`：core 模块加在 core 区、feature 加在 feature 区。

4. **包结构**：`src/main/java/com/zkyjss/eyeproject/<...>/`；库模块不需要 AndroidManifest.xml（namespace 已在 gradle 声明）。

5. **依赖规则自检**（NIA 铁律，详见 `docs/NIA项目开发规范.md` §2.2）：
   - core 之间可依赖，**core 禁止依赖 feature / app**
   - **feature 禁止依赖 feature**（跨 feature 跳转由 app 的 NavHost 接线）
   - 对外暴露的基础设施用 `api`，实现细节用 `implementation`（参照 `docs/NIA项目开发规范.md` §6.3）

6. **DI 约定**：新模块若含接口 + 实现绑定，`@Module` 放本模块 `di/` 子包（参照 `core/data/di/DataModule.kt`）；**不要**把绑定写进 `app/di/AppModule`（app 只装 domain/feature 端口）。

7. **模块文档骨架**：在模块根目录建 `<模块名大写>.md`（如 `core/network/NETWORK.md`），骨架：

   ```markdown
   # <模块路径>

   > 一句话定位

   ## 职责

   ## 对外 API

   | 类型 | 说明 |
   |---|---|

   ## 依赖关系

   - **依赖**：
   - **被依赖**：

   ## 注意事项
   ```

8. **收尾**：`./gradlew.bat :app:compileDebugKotlin` 验证注册无误；在根 `CLAUDE.md` 的模块地图里补一行。
