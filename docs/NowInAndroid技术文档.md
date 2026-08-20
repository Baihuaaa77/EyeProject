# Now in Android（NIA）技术文档 —— 官方多模块架构最佳实践

> 本文档整理自 Google 官方 [Now in Android](https://github.com/android/nowinandroid) 项目，
> 说明其架构设计、模块化、约定插件、分层与数据流，并对照 EyeProject 的落地方式。

## 1. 项目定位

Now in Android（简称 NIA）是 Google 官方的「现代 Android 应用」参考实现，示范如何用
**Kotlin + Jetpack Compose + 官方推荐架构 + 多模块** 构建一个可维护、可测试、可扩展的应用。
它不是一个产品，而是一份「照着做」的活文档。

NIA 的三大核心理念：

1. **模块化**：按 `core` / `feature` / `app` 分层，配合 `build-logic` 约定插件。
2. **单向数据流（UDF）**：`UiState` 由 ViewModel 产出，UI 只消费并上报事件。
3. **职责单一、依赖单向**：`core` 绝不依赖 `feature`，依赖方向始终向下。

## 2. 顶层结构

```
nowinandroid/
├── app/                     # 唯一 application 模块：入口、导航、DI 装配
├── build-logic/             # 约定插件（convention plugins，模块化基石）
│   └── convention/
├── core/                    # 可复用、无业务 UI 的基础层
│   ├── common/              # 通用工具（Result、Dispatchers、扩展函数）
│   ├── data/                # 仓储实现 + 数据源
│   ├── database/            # Room 实体 / DAO / 数据库
│   ├── datastore/           # Preferences DataStore（用户偏好）
│   ├── datasource/          # 远程数据源（Retrofit）
│   ├── designsystem/        # 设计令牌（Color/Type/Shape/Theme）
│   ├── domain/              # 领域模型 + 用例（UseCase）
│   ├── model/               # 纯数据模型（无 Android 依赖）
│   ├── network/             # 网络层（Retrofit + kotlinx-serialization）
│   ├── notifications/       # Notifier 抽象 + SystemNotifier 实现
│   ├── testing/             # 测试工具（MainDispatcherRule、测试替身）
│   └── ui/                  # 可复用 UI 组件（依赖 designsystem）
├── feature/                 # 每个功能一个模块
│   ├── foryou/  topic/  interests/  search/  bookmarks/  settings/
├── sync/
│   └── work/                # WorkManager 后台同步（Synchronizer）
└── gradle/
    └── libs.versions.toml   # 版本目录（单一版本来源）
```

## 3. build-logic：约定插件（模块化的基石）

这是 NIA 最有辨识度的设计。每个 Android 模块都要重复的配置（compileSdk、minSdk、Java 版本、
Compose、Hilt、lint…）被抽成**约定插件**，放在 `build-logic/convention`（一个 `kotlin-dsl` 的 included build）。

模块的 `build.gradle.kts` 从几十行重复配置变成几行声明：

```kotlin
// 某个 feature 模块
plugins {
    id("nowinandroid.android.feature")   // 组合：library + compose + hilt
}
```

约定插件内部按组合复用，典型实现：

```kotlin
// AndroidFeatureConventionPlugin.kt
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("nowinandroid.android.library")
            pluginManager.apply("nowinandroid.android.library.compose")
            pluginManager.apply("nowinandroid.android.hilt")
            // 特性模块依赖 core:ui 等
        }
    }
}
```

约定插件的价值：
- **统一构建配置**：SDK 版本、编译选项、lint 规则改一处生效全局。
- **消除重复**：模块 build 文件只写「类型 + 依赖」。
- **约束依赖方向**：约定插件只暴露 `api` 少量公共依赖，避免 `core` 意外依赖 `feature`。

> **AGP 9 + 内置 Kotlin 的注意点**（EyeProject 实践）：约定插件里对 AGP DSL 类型的引用
> （`extensions.configure<LibraryExtension>`）需要把 AGP 依赖声明为 `implementation` 而非
> `compileOnly`，否则插件运行时实例化会报 `NoClassDefFoundError`（Gradle 9 行为与 NIA 所用的
> Gradle 8 不同）。

## 4. 模块分层与依赖方向

依赖方向严格遵守「只能向下」：

```
feature:*  ──►  core:*  ──►  core:model（最底层）
   └────────────►  app 只依赖 feature + 必要 core
```

规则：
- `core` 模块之间只向更底层依赖，`core:model` 是最底层（纯 Kotlin，无 Android）。
- `core` 绝不依赖 `feature`、绝不依赖 `app`。
- `feature` 之间互不依赖，跨 feature 跳转通过导航（app 层）完成。
- `app` 是唯一 application 模块，负责入口 + 导航图 + DI 装配。

## 5. 依赖注入（Hilt）

- `@HiltAndroidApp` 标注 `Application`。
- `@AndroidEntryPoint` 标注 Activity/Fragment/View/Service/BroadcastReceiver。
- `@HiltViewModel` + `@Inject constructor` 注入 ViewModel。
- 抽象用 `interface`，实现用 `@Binds`（如 `Notifier` → `SystemNotifier`）。

EyeProject 落地：`app` 用 `@HiltAndroidApp`、`@AndroidEntryPoint`；`AppModule` 用 `@Binds` 把
`ColorTemperatureController`/`ReminderController`/`Notifier` 接口绑定到实现；`HomeViewModel` 用
`@HiltViewModel` 注入。

## 6. 数据层（core:data / database / datastore / network）

NIA 采用 **Repository 模式 + 离线优先（Offline First）**：

```
UI ──► ViewModel ──► Repository（core:data）
                         ├── 本地数据源（Room，core:database）
                         └── 远程数据源（Retrofit，core:network）
```

- **Repository**（`OfflineFirstNewsRepository`）：对外暴露 `Flow`，内部编排本地/远程 + 同步。
- **Syncable / Synchronizer**：`sync/work` 里的 WorkManager 定期拉取并写入本地。
- **DataStore**：用户偏好（主题、已关注话题）用 Preferences DataStore（协程 + Flow，替代 SharedPreferences）。
- **Room**：本地缓存与持久化。

EyeProject 落地（单机无网络，做减法）：
- `core:data` 直接提供 `ScreenUsageRepository`（UsageStatsManager）、`AmbientLightMonitor`（光线传感器 Flow）、
  `ScreenBrightnessReader`、`AppSettings`（SharedPreferences，未来可迁 DataStore）。
- 无 Room/网络，故无 `database`/`network`/`sync`。

## 7. 领域层（core:domain）

- 放**与 UI 无关的纯业务逻辑**，用 **UseCase** 表达单一动作。
- UseCase 是「一个类 + `operator fun invoke`」，便于注入、单测、复用。

```kotlin
class GetUserNewsResourcesUseCase @Inject constructor(
    private val newsRepository: NewsRepository,
    private val userDataRepository: UserDataRepository
) {
    operator fun invoke(): Flow<List<UserNewsResource>> = ...
}
```

EyeProject 落地：`core:domain` 放 `DistanceEstimator`（40cm 标定距离估算纯逻辑）以及
`ColorTemperatureController`/`ReminderController` 等控制接口（由 app 层实现）。

## 8. UI 层（Compose + ViewModel + UDF）

- 每个 feature 模块有自己的 `Screen` + `ViewModel` + `UiState`。
- **UDF**：`ViewModel` 暴露 `StateFlow<UiState>`，`Screen` 用 `collectAsStateWithLifecycle()` 收集，
  用户事件通过方法回调（`onClick`）上报给 ViewModel。
- `UiState` 是 `@Immutable data class`，包含该屏全部渲染所需状态。

```kotlin
data class ForYouUiState(
    val feedState: FeedState = Loading,
    val isSyncing: Boolean = false,
)

class ForYouViewModel @Inject constructor(...) : ViewModel() {
    val uiState: StateFlow<ForYouUiState> = ...
    fun refresh() { ... }
}
```

EyeProject 落地：`feature:home` 的 `HomeUiState` + `HomeViewModel`（收集摄像头帧 Flow、距离估算、
使用时长/亮度/光照，产出单一 `StateFlow<HomeUiState>`），`HomeScreen` 纯声明式消费。

## 9. 设计系统（core:designsystem + core:ui）

NIA 把设计系统拆两层：

- `core:designsystem`：**设计令牌** —— `Color`/`Typography`/`Shape`/`Theme`，只依赖 Compose 基础，
  不含业务组件。提供 `NiaTheme`、明暗两套配色（支持动态色 dynamic color）。
- `core:ui`：**可复用组件** —— 依赖 designsystem，提供 `NiaTopAppBar`、加载态、空态、占位图等通用组件。

EyeProject 落地（同样拆分）：
- `core:designsystem`：`EyeColors`/`EyeTypography`/`EyeShapes`/`EyeProjectTheme`（CompositionLocal 自定义主题，
  无 Material 默认配色）。
- `core:ui`：`EyeButton`/`EyeMetricCard`/`EyeStatusChip`/`EyeSegmentedRow`/`EyeTopBar`/`EyeText`（BasicText 原语）。

## 10. 导航

NIA 用 **Navigation Compose + 类型安全路由**（`@Serializable` route object），导航图集中在 app 的 `NiaNavHost`，
每个 feature 暴露自己的 `Screen`，跨 feature 跳转由导航完成（避免 feature 间互相依赖）。

EyeProject 是单屏应用，故未引入导航，`MainActivity` 直接 `setContent { EyeProjectTheme { HomeScreen() } }`。

## 11. 测试策略

- `core:testing` 提供 `MainDispatcherRule`（把主调度器替换为 `UnconfinedTestDispatcher`）等测试工具。
- 仓储层单测用**假数据源（fake）**，ViewModel 用 `MainDispatcherRule` 测状态流。
- UI 用 Compose UI Test + 语义树断言。

EyeProject 落地：已加 `core:testing`（`MainDispatcherRule`），后续补单测时可复用。

## 12. 构建与工具链

- **Version Catalog**（`gradle/libs.versions.toml`）：版本单一来源。
- **约定插件**（build-logic）：统一构建配置。
- **JDK 17 / AGP / Kotlin / Compose BOM**：Kotlin 2.x 用 Compose 编译器插件（`org.jetbrains.kotlin.plugin.compose`）。

## 13. EyeProject 对照总结

| NIA 概念 | EyeProject 落地 |
|---|---|
| build-logic 约定插件 | `build-logic/convention`（5 个插件） |
| core:designsystem / core:ui | 同构拆分（令牌 / 组件） |
| core:common | 通用格式化工具 |
| core:model / domain / data | 同构 |
| core:notifications | `Notifier` + `SystemNotifier`（提醒/色温通知） |
| core:testing | `MainDispatcherRule` |
| core:database / network / datastore / sync | 无（单机无网络，做减法） |
| feature:* | feature:camera / feature:home |
| app | MainActivity + Hilt DI + 系统组件 |
