---
name: new-screen
description: 在 EyeProject 新增页面/路由时使用。按 NIA 导航三件套规范生成路由对象、navigateToX 扩展、NavGraphBuilder 扩展，并接线到 app 的 EyeProjectNavHost；顶层 Tab 同步更新 TopLevelDestination。参数：<feature名> <页面名> [top-level]
argument-hint: <feature名> <页面名> [top-level]
---

# 新增页面脚手架

参数：`<feature名> <页面名> [top-level]`（如 `services presbyopia-result`、`fatigue settings top-level`）。
用户未给全参数时先问清：属于哪个 feature、是否顶层 Tab、页面入口从哪来（哪个页面触发跳转）。

## 步骤

1. **确认 feature 模块存在**：`feature/<name>/` 不存在则提示先执行 `/new-module`，不要顺手建模块。

2. **导航三件套**（参照 `feature/services/src/main/java/com/zkyjss/eyeproject/feature/services/navigation/ServicesNavigation.kt` 的既有风格）：
   在 `feature/<name>/src/main/java/com/zkyjss/eyeproject/feature/<name>/navigation/<Name>Navigation.kt` 中：

   ```kotlin
   @Serializable
   data object <Name>Route

   fun NavController.navigateTo<Name>(navOptions: NavOptions? = null) {
       navigate(<Name>Route, navOptions)
   }

   fun NavGraphBuilder.<name>Screen(onBack: () -> Unit) {
       composable<<Name>Route> {
           <Name>Screen(onBack = onBack)
       }
   }
   ```

   - 路由对象命名 `<页面名>Route`，文件放 `navigation/` 子包
   - 若本模块还没有 `kotlinx.serialization` 的使用，确认其 build 文件已挂 `eyeproject.kotlin.serialization`

3. **页面本体**：`<Name>Screen.kt` + 可选 `<Name>ViewModel.kt` / `<Name>UiState.kt`，签名遵循项目惯例：

   ```kotlin
   @Composable
   fun <Name>Screen(
       onBack: () -> Unit,
       modifier: Modifier = Modifier,
       viewModel: <Name>ViewModel = hiltViewModel(),
   )
   ```

   - ViewModel：`@HiltViewModel` + `uiState: StateFlow<XUiState>`（单向数据流，NIA 规范 §3）
   - 事件全部是 lambda 参数上抛；返回栏用 `TopBackBar`

4. **接线 app**：`app/src/main/java/com/zkyjss/eyeproject/ui/EyeProjectNavHost.kt` 的 `NavHost` 块内加：

   ```kotlin
   <name>Screen(onBack = { navController.navigateUp() })
   ```

5. **顶层 Tab 才需要**（三处同步，参照 `TopLevelDestination.HOME/SERVICES/MINE`）：
   - `ui/TopLevelDestination.kt` 枚举加项
   - `EyeProjectNavHost.kt` 的 `routeName()` 加分支（返回 `<Name>Route::class.qualifiedName.orEmpty()`）
   - `navigateToTopLevelDestination()` 的 when 加分支（用 `navOptions { popUpTo(start) { saveState = true }; launchSingleTop = true; restoreState = true }`）

6. **收尾**：更新该 feature 模块文档（模块根目录 `.md`）的页面与导航清单；改动含新依赖时跑
   `./gradlew.bat :app:compileDebugKotlin` 验证。

## 禁止

- 不在 feature 里 import 另一个 feature 的任何类型（跨 feature 跳转由 app 接线）
- 不把样式写死在 Screen 里（一律读 `LocalEyeColors` / `LocalEyeTypography` / `EyeShapes`）
