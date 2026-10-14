# NIA 项目开发规范

> **依据**：[android/nowinandroid](https://github.com/android/nowinandroid) `main` 分支官方文档（2026-09 检索）——
> `docs/ArchitectureLearningJourney.md`、`docs/ModularizationLearningJourney.md`、`build-logic/README.md`、根 `README.md`、
> 17 个约定插件源码清单、`settings.gradle.kts` 及 feature/core 模块构建配方。
> **用途**：本项目（EyeProject）日常开发的查阅基线。标注「官方实践」的条目来自官方代码观察，其余均有官方文档出处。
> EyeProject 与 NIA 的差异对照见文末第 9 节。

---

## 0. 快速判定清单

做任何新改动前过一遍：

- [ ] 新代码放在哪一层？→ UI 展示进 `feature`，业务规则进 `core:domain`，数据进出进 `core:data`，纯结构进 `core:model`
- [ ] 新类型只被一个 feature 用？→ 留在该 feature；被两个以上用？→ 下沉 core
- [ ] feature 之间是否出现了依赖？→ 立即重构为依赖对方 api（导航键）或下沉 core
- [ ] 新页面是否走了「NavKey + navigate 扩展 + NavGraphBuilder 扩展」三件套？
- [ ] 仓库读数据是否返回 Flow（流），而非快照 getter？
- [ ] UiState 是否是 sealed interface、UI 是否处理了所有状态（Loading/Success/…）？
- [ ] DI 绑定是否写在提供实现的模块内（而不是 app 层）？
- [ ] 新模块是否只挂了必要的约定插件、依赖用的是 api 还是 implementation 有没有想清楚？
- [ ] 对应模块的单测/截图测试写了吗？测试是否避免 mock 库？

---

## 1. 架构总纲

### 1.1 三层架构

NIA 严格遵循 [官方应用架构指南](https://developer.android.com/topic/architecture)，分三层：

```
┌─────────────────────────────────────┐
│ UI 层    Screen(Compose) + ViewModel │  ← feature 模块
├─────────────────────────────────────┤
│ Domain 层  UseCase（可选）            │  ← core:domain
├─────────────────────────────────────┤
│ Data 层   Repository + DataSource    │  ← core:data（及 database/datastore/network）
└─────────────────────────────────────┘
```

- 上层只响应下层的变更；**事件向下流，数据向上流**（UDF，单向数据流）
- 数据流用 Kotlin Flow 实现
- 注意：官方架构 ≠ Clean Architecture，后者的概念（如 Repository 接口必须放 domain）在 NIA 中**不适用**

### 1.2 技术选型基线

| 领域 | 官方选型 |
|---|---|
| UI | Jetpack Compose 全量 + 自建设计系统（封装 Material 3） |
| 状态 | ViewModel + StateFlow（`stateIn`） |
| DI | Hilt |
| 持久化 | Room（结构化）+ Proto DataStore（用户偏好） |
| 网络 | Retrofit + kotlinx-serialization |
| 后台 | WorkManager（同步任务） |
| 序列化 | kotlinx-serialization |
| 模块图自动生成 | 每模块 README 内嵌依赖图（CI 的 `graphUpdate` 任务维护） |

---

## 2. 模块化规范

### 2.1 模块类型（官方定义）

| 类型 | 职责 | 示例 |
|---|---|---|
| `app` | 脚手架：`MainActivity`、`NiaApp`、应用级受控导航（`NiaNavHost`、`TopLevelDestination`）。依赖全部 feature 和所需 core | `:app` |
| `feature:*:api` | **只含导航键（NavKey）**，供其他 feature 跳转到本 feature | `:feature:topic:api`（`TopicNavKey`、`Navigator.navigateToTopic`） |
| `feature:*:impl` | 该功能的全部其余内容：Screen、ViewModel、子组件 | `:feature:foryou:impl` |
| `core:*` | 共享库模块，无业务 UI | data / database / datastore / network / model / ui / designsystem / domain / common / notifications / navigation / analytics / testing |
| 杂项 | sync（WorkManager 同步）、benchmarks（Macrobenchmark + Baseline Profile）、lint（自定义 lint）、`app-nia-catalog`（设计系统目录应用） | `:sync:work`、`:benchmarks` |

### 2.2 依赖规则（铁律）

来自 ModularizationLearningJourney，**违反任何一条都需要重构**：

1. **`core` 模块之间可以互相依赖，但绝不依赖 feature / app**
2. **feature 的 `api` 不依赖任何其他 feature 的 `api` 或 `impl`**
3. **feature 的 `impl` 只能依赖其他 feature 的 `api`**（不能依赖 impl）
4. 两个子模块都只依赖自己需要的 core 模块
5. **app 依赖所有 feature 和所需 core**（顶层汇聚点，无人依赖 app）
6. 依赖箭头语义：实线 = `api` 依赖（传递可见），虚线 = `implementation`（不传递）——模块图里严格区分

### 2.3 feature 的 api / impl 拆分

- 拆分动机：**让 feature 之间只通过导航键解耦**——A 跳 B 只需要 B 的 NavKey，不需要 B 的实现
- `api` 模块的典型构建配方（官方 `:feature:foryou:api`）：

```kotlin
plugins { alias(libs.plugins.nowinandroid.android.feature.api) }
dependencies { api(projects.core.navigation) }
```

- `impl` 模块配方（官方 `:feature:foryou:impl`）：

```kotlin
plugins {
    alias(libs.plugins.nowinandroid.android.feature.impl)   // 内含 library + hilt 等组合
    alias(libs.plugins.nowinandroid.android.library.compose)
    alias(libs.plugins.roborazzi)                            // 截图测试
}
dependencies {
    implementation(projects.core.domain)
    implementation(projects.feature.foryou.api)   // 自己的 api
    implementation(projects.feature.topic.api)    // 跳转目标 feature 的 api（只允许 api！）
    ...
    testImplementation(projects.core.testing)
}
```

### 2.4 粒度原则（何时拆、何时不拆）

官方明确说明（ModularizationLearningJourney「Further considerations」）：

- 模块化没有唯一正确答案，按项目规模平衡，**避免过度模块化**
- 数据层小 → 单个 `core:data` 足够；repository / 数据源数量增长后再拆
- **类归属判定**：只被一个 feature 用 → 留在该 feature；被多处用 → 移入合适的 core 模块

---

## 3. UI 层规范

### 3.1 ViewModel 与 UiState

- UI 状态建模为 **sealed interface 层级**（接口 + 不可变 data class），如 `NewsFeedUiState.Loading / Success`
- 状态只经由「数据流变换」产出，保证：① UI 状态始终反映底层数据（数据是唯一事实源）；② UI 处理所有可能状态
- ViewModel 从 use case / repository 收集冷流，`combine` / `map` 成单一 UiState 流，再 `stateIn` 转 StateFlow 暴露
- 官方仓库实践：`stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)`

### 3.2 事件处理

- 用户操作 = **普通方法调用**，以 lambda 形式从 ViewModel 传给 Screen（如 `followTopic`）
- Screen 不持有业务逻辑，只渲染状态 + 上抛事件
- NIA 的 domain 层**不含事件处理用例**（官方注明 "for now"）——事件由 UI 层直接调用 repository 方法

### 3.3 Screen 组件

- 每个 feature 一组 `XScreen`（Composable）+ `XViewModel`（`@HiltViewModel`，`hiltViewModel()` 获取）
- `XScreen` 的导航/操作回调一律是函数参数（`onBack`、`onTopicClick` 等），由 app 的 NavHost 或上层注入

### 3.4 导航规范

- 导航键（`@Serializable`/NavKey 类型）放在 **feature 的 api 模块** 或 `core:navigation`
- 官方仓库实践：每个 feature 一个 `navigation/XNavigation.kt`，包含三件套：
  1. `@Serializable data object XRoute`（路由键）
  2. `fun NavController.navigateToX(navOptions)`（跳转扩展）
  3. `fun NavGraphBuilder.xScreen(onBack: () -> Unit)`（图构建扩展，内部 `composable<XRoute> { XScreen(...) }`）
- app 持有 `NavHost`（`NiaNavHost`）与底部导航（`TopLevelDestination`），顶层 Tab 切换用 `popUpTo(start) { saveState/restoreState } + launchSingleTop`
- EyeProject 现行做法（`navigation/` 子包）与上述三件套一致

### 3.5 designsystem 与 core:ui 的边界

| 模块 | 内容 | 依赖 |
|---|---|---|
| `core:designsystem` | 主题（`NiaTheme`）、颜色/排版/形状令牌、**基础**组件（`NiaButton`、`NiaIcons`）、图标资源 | 无（不依赖其他 core） |
| `core:ui` | **组合**组件，渲染业务模型（如 `NewsFeed`） | designsystem + 数据层模型 |

- 判定：组件是否理解业务模型？理解 → `core:ui`；不理解 → `core:designsystem`
- EyeProject 差异：我们的 `core:ui` 组件刻意不依赖业务模型（见 §9.2）

---

## 4. Domain 层规范

- UseCase = 拥有**单一可调用方法**（`operator fun invoke`）的类，承载业务逻辑
- 用途：组合 / 变换多个 repository 的流，为 ViewModel 消除重复逻辑
- 示例：`GetUserNewsResourcesUseCase` 把 `NewsRepository` 的新闻流与 `UserDataRepository` 的用户数据流 combine 成 `UserNewsResource` 流
- 定位：**可选层**——逻辑简单时 ViewModel 直接调 repository 是允许的（官方架构立场）

---

## 5. Data 层规范

### 5.1 Repository

- Repository 是其他层访问数据的**唯一公开 API**
- **读 = 数据流**：`fun getTopics(): Flow<List<Topic>>`。禁止快照 getter（`getModel()`）——返回时数据可能已失效
- **写 = suspend 函数**：`suspend fun toggleFollowedTopicId(id, followed)`，由调用方决定作用域
- 读本地不期望出错（本地是事实源）；错误主要发生在与远端 reconcile 时
- Repository 负责把**层内模型**（如 Room 的 `PopulatedNewsResource`）变换为**公开模型**（`NewsResource`），以流中间算子的形式完成——层内模型不外泄

### 5.2 数据源（DataSource）

每个 Repository 依赖一或多个数据源，典型三类（官方 `OfflineFirstTopicsRepository`）：

| 数据源 | 载体 | 用途 |
|---|---|---|
| `XDao` | Room/SQLite | 结构化持久数据 |
| `XPreferencesDataSource` | Proto DataStore | 用户偏好等非结构化持久数据 |
| `XNetworkDataSource` | Retrofit REST API | 远端数据 |

### 5.3 Offline-first 与同步

- 数据层是 **offline-first**：本地存储是全部数据的事实源（source of truth）
- 远端数据一到手**立即写入本地**，再由本地流对外发射——读写关注点分离
- 同步失败用**指数退避**，委托给 WorkManager 的 `SyncWorker`（实现 `Synchronizer` 接口）；应用启动时 `Sync.initialize` 入队全仓库同步
- 仓库命名（官方实践）：`OfflineFirstXRepository`（接口 `XRepository` 同文件）

### 5.4 DI 装配

- 接口 + 实现 + `@Binds` 模块**同处一个 core 模块**（如 `core:notifications/di/NotifierModule.kt`），app 层不做数据层装配
- 依赖可见性参照官方 `core:data`：对外暴露的基础设施用 `api`（common/database/datastore/network），实现细节用 `implementation`（analytics/notifications）

---

## 6. 构建规范（build-logic）

### 6.1 约定插件哲学（官方 README 原文要点）

- 目的：**单一事实源**管理公共模块配置，消除重复 build 脚本，避开 `buildSrc` 的坑
- 插件是 **additive（累加式）与 composable（可组合）** 的，每个插件只做**单一职责**，模块按需挑选
- **一次性的模块专属配置直接写在模块 `build.gradle.kts`，不要为它造约定插件**
- 插件之间共享逻辑用 build-logic 内的公共 Kotlin 文件

### 6.2 官方约定插件清单（17 个，`build-logic/convention`）

| 插件 id（nowinandroid.*） | 作用 |
|---|---|
| `android.application` / `android.library` | Android 应用/库公共配置（SDK、Java 版本） |
| `android.application.compose` / `android.library.compose` | Compose 编译器 + buildFeatures |
| `android.application.flavors` | demo/prod 产品风味 |
| `android.application.firebase` | Firebase（Crashlytics/性能监控） |
| `android.application.jacoco` / `android.library.jacoco` | 覆盖率 |
| `android.feature.api` / `android.feature.impl` | feature 两子模块的标准组合 |
| `android.room` | Room（schema 导出等） |
| `android.lint` | 自定义 lint 配置 |
| `android.test` | 测试模块配置 |
| `hilt` | Hilt + KSP |
| `jvm.library` | 纯 JVM/Kotlin 库 |
| （`RootPlugin`） | 根工程配置 |

### 6.3 模块构建配方

- 所有模块：`alias(libs.plugins.nowinandroid.*)` 挂插件 + `namespace` + 依赖三段，构建文件里**只有差异项**
- 依赖一律用类型安全访问器 `projects.core.data` / `libs.androidx.core.ktx`
- 版本**只在 `gradle/libs.versions.toml` 一处**定义（build-logic 也读 catalog），禁止双份维护

### 6.4 settings.gradle.kts 规则

- `includeBuild("build-logic")` 写在 **pluginManagement 块内**
- `repositoriesMode = FAIL_ON_PROJECT_REPOS`（模块内禁止声明仓库）
- google 仓库用 `includeGroupByRegex("com\\.android.*" / "com\\.google.*" / "androidx.*")` 过滤加速

---

## 7. 测试规范

### 7.1 总原则（根 README 原文要点）

- **不使用任何 mock 库**（Mockito/MockK 均不用）
- 生产实现用**测试替身（test doubles）替换**：替身实现与生产相同的完整接口，提供简化但真实的实现 + **测试钩子**（如 `TestXRepository` 可手动推数据、发错误）
- 好处：测试更不脆，走过更多真实生产代码，而不是校验「某方法被调用过」

### 7.2 具体策略

| 场景 | 官方做法 |
|---|---|
| ViewModel 单测 | 构造注入 `Test` 仓库替身，操作钩子 → 验证状态输出 |
| 仪器测试 | 用**临时目录 + 真 DataStore**（每测后清空），跑真实代码而非 mock 数据流 |
| 测试工具 | `core:testing`（`MainDispatcherRule`、`NiaTestRunner`、共享替身）；`core:datastore-test` / `core:data-test` / `sync:sync-test` 提供专用测试模块 |
| 截图测试 | Roborazzi：`verifyRoborazziX` 校验 / `recordRoborazziX` 录制 / `compareRoborazziX` 出对比图；已知正确图由 CI（Linux）录制 |
| 运行任务 | 用变体限定任务 `testDemoDebug` / `connectedDemoDebugAndroidTest`；**不要裸跑 `./gradlew test`**（会跑所有变体且多数无测试） |

---

## 8. 质量与性能基建

| 基建 | 说明 |
|---|---|
| `lint` 模块 | 自定义 lint 规则集中地（`AndroidLintConventionPlugin` 配置） |
| Baseline Profile | `app/src/main/baseline-prof.txt`，benchmark 变体 + AOSP 模拟器上生成；改动启动路径后需重新生成 |
| Macrobenchmark | `:benchmarks` 模块测启动性能 + 生成 baseline profile |
| Compose 编译器指标 | `./gradlew assembleRelease -PenableComposeCompilerMetrics=true -PenableComposeCompilerReports=true`，输出到 `build/compose-reports|metrics`（排查重组性能） |
| 模块依赖图 | 每模块 README 内嵌，CI 自动更新（`graphUpdate` 任务） |

---

## 9. EyeProject 对齐状态

### 9.1 约定插件对照

| EyeProject 插件 id | NIA 对应 | 状态 |
|---|---|---|
| `eyeproject.android.application` / `.library` | `nowinandroid.android.application` / `.library` | ✅ 对齐 |
| `eyeproject.android.application.compose` / `.library.compose` | 同名 | ✅ 对齐 |
| `eyeproject.android.hilt` | `nowinandroid.hilt` | ✅ 对齐 |
| `eyeproject.kotlin.serialization` | NIA 在模块内直接 `id("kotlinx-serialization")` | 等价实现 |
| —— | flavors / firebase / jacoco / lint / room / test / jvm.library / feature.api / feature.impl | ⬜ 未启用（按需） |

### 9.2 差异清单与升级时机

| 项 | NIA 规范 | EyeProject 现状 | 升级时机 |
|---|---|---|---|
| feature 拆分 | api / impl 两子模块 | 单模块 + `navigation/` 子包（三件套齐全） | feature 间互相跳转变多、或需要给测试/其他 app 复用导航键时拆分 |
| 数据持久化 | Room + Proto DataStore | JSON 文件 + SharedPreferences | 数据量/查询复杂度上升、需要迁移策略时引入 Room |
| 网络 | Retrofit + 离线同步 | 无网络层 | 接入云端（如智能体后端）时新建 `core:network`，Repository 接口先行 |
| 后台同步 | WorkManager + `Synchronizer` | 前台测试相机 + AlarmManager（提醒） | **新增「定时拉取/上传」类任务时再引入 WorkManager** |
| core:ui 依赖 | 可依赖数据层模型渲染组合组件 | 组件不依赖业务模型（纯通用件） | 出现「多 feature 共用的业务组合组件」时再放宽 |
| 测试 | 无 mock 库 + Test 替身体系 + Roborazzi | 仅 `app` 下少量 androidTest（`core:testing` 因 0 消费方已删除） | **下一步优先项**：给 `BlinkDetector`/`DepthFromIris`/ViewModel 建单测；写第一个单测时按需重建 `core:testing`（一个 build 文件 + `MainDispatcherRule`） |
| lint / benchmark / baseline profile | 全套启用 | 未启用 | 稳定后引入 lint 模块；性能问题出现时启用 benchmark |
| 版本管理 | 单一 version catalog | build-logic 与 toml 双份 | 尽快收敛（让 build-logic 读 catalog） |

### 9.3 已确认对齐的关键实践

- 三层 + UDF（`StateFlow<UiState>` + lambda 事件上抛）✅
- 依赖铁律 1~5（core 不依赖 feature；app 顶层汇聚）✅（camera 下沉后）
- 导航三件套 + app 持有 NavHost + 底部 Tab 状态恢复 ✅
- DI 绑定随模块（NotifierModule）✅
- designsystem 令牌化、组件不写死样式 ✅
- settings：FAIL_ON_PROJECT_REPOS + google 仓库正则过滤 + build-logic 复合构建 ✅

---

*本文档由官方仓库文档整理；NIA 演进较快（如 feature api/impl 拆分为 2025 年后新结构），重大升级时建议重新核对官方仓库。*
