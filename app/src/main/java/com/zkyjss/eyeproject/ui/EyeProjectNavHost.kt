package com.zkyjss.eyeproject.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.remember
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import androidx.compose.foundation.layout.Box
import com.zkyjss.eyeproject.core.ui.component.EyeNavigationDock
import com.zkyjss.eyeproject.core.ui.component.EyeNavigationItem
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.feature.home.HomeNavigation
import com.zkyjss.eyeproject.feature.home.navigation.HomeRoute
import com.zkyjss.eyeproject.feature.home.navigation.eyeCareScreens
import com.zkyjss.eyeproject.feature.home.navigation.navigateToEyeChat
import com.zkyjss.eyeproject.feature.home.navigation.homeScreen
import com.zkyjss.eyeproject.feature.home.navigation.navigateToEyeAdvice
import com.zkyjss.eyeproject.feature.home.navigation.navigateToEyeBrightness
import com.zkyjss.eyeproject.feature.home.navigation.navigateToEyeTrend
import com.zkyjss.eyeproject.feature.home.navigation.navigateToHome
import com.zkyjss.eyeproject.feature.mine.MineNavigation
import com.zkyjss.eyeproject.feature.mine.navigation.MineRoute
import com.zkyjss.eyeproject.feature.mine.navigation.mineScreen
import com.zkyjss.eyeproject.feature.mine.navigation.mineSubScreens
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToAccount
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToCloudSync
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToMine
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToPermissions
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToRecords
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToSubjectEdit
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToSubjects
import com.zkyjss.eyeproject.feature.mine.navigation.subjectEditScreen
import com.zkyjss.eyeproject.feature.services.navigation.ServicesRoute
import com.zkyjss.eyeproject.feature.services.navigation.dryEyeScreen
import com.zkyjss.eyeproject.feature.services.navigation.navigateToSystemSettings
import com.zkyjss.eyeproject.feature.services.navigation.systemSettingsScreen
import com.zkyjss.eyeproject.feature.services.navigation.navigateToDryEye
import com.zkyjss.eyeproject.feature.services.navigation.navigateToPresbyopia
import com.zkyjss.eyeproject.feature.services.navigation.navigateToServices
import com.zkyjss.eyeproject.feature.services.navigation.navigateToStrabismus
import com.zkyjss.eyeproject.feature.services.navigation.navigateToColorVision
import com.zkyjss.eyeproject.feature.services.navigation.colorVisionScreen
import com.zkyjss.eyeproject.feature.services.navigation.presbyopiaScreen
import com.zkyjss.eyeproject.feature.services.navigation.servicesScreen
import com.zkyjss.eyeproject.feature.services.navigation.strabismusScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.blinkTestScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.gazeScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToBlinkTest
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToGaze
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToTestModel
import com.zkyjss.eyeproject.feature.testmodel.navigation.testModelScreen

/** 对标 NIA 的 NiaNavHost：持有 NavHost 与底部导航栏。 */
@Composable
fun EyeProjectNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val colors = LocalEyeColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val debuggable = remember { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    // 顶层页右上角：账户头像；系统设置在「我的」里，都是完整页面
    val openSettings = { navController.navigateToSystemSettings() }
    val openAccount = { navController.navigateToAccount() }
    val back: () -> Unit = { navController.navigateUp() }
    val openTest: (String) -> Unit = { kind ->
        when (kind) {
            "DRY_EYE" -> navController.navigateToDryEye()
            "PRESBYOPIA" -> navController.navigateToPresbyopia()
            "STRABISMUS" -> navController.navigateToStrabismus()
            "COLOR_VISION" -> navController.navigateToColorVision()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.appBackground,
        bottomBar = {
            if (currentDestination.isTopLevelDestination()) {
                EyeProjectBottomBar(navController = navController, currentDestination = currentDestination)
            }
        },

    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            // 品牌过渡：淡入 + 轻微上浮，退出只淡出，避免页面「飞来飞去」
            enterTransition = { fadeIn(tween(EyeMotion.MEDIUM)) + slideInVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) { it / 24 } },
            exitTransition = { fadeOut(tween(EyeMotion.SHORT)) },
            popEnterTransition = { fadeIn(tween(EyeMotion.MEDIUM)) },
            popExitTransition = { fadeOut(tween(EyeMotion.SHORT)) + slideOutVertically(tween(EyeMotion.MEDIUM)) { it / 24 } },
        ) {
            homeScreen(HomeNavigation(
                onOpenAccount = openAccount,
                onOpenSettings = openSettings,
                // 开发者工具（模型测试）只在可调试包里出现
                onNavigateToTestModel = if (debuggable) { { navController.navigateToTestModel() } } else null,
                onOpenTest = openTest,
                onEditProfile = { navController.navigateToSubjectEdit(it) },
                onOpenTrend = { navController.navigateToEyeTrend() },
                onOpenAdvice = { navController.navigateToEyeAdvice() },
                onOpenChat = { navController.navigateToEyeChat() },
                onOpenBrightness = { navController.navigateToEyeBrightness() },
            ))
            eyeCareScreens(onBack = back, onOpenTest = openTest, onOpenAccount = openAccount)
            servicesScreen(
                onDryEyeClick = { navController.navigateToDryEye() },
                onPresbyopiaClick = { navController.navigateToPresbyopia() },
                onStrabismusClick = { navController.navigateToStrabismus() },
                onColorVisionClick = { navController.navigateToColorVision() },
                onOpenSettings = openSettings,
                onOpenAccount = openAccount,
            )
            mineScreen(MineNavigation(
                onOpenSettings = openSettings,
                onOpenAccount = openAccount,
                onEditSubject = { navController.navigateToSubjectEdit(it) },
                onOpenRecords = { navController.navigateToRecords() },
                onOpenSubjects = { navController.navigateToSubjects() },
                onOpenCloudSync = { navController.navigateToCloudSync() },
                onOpenPermissions = { navController.navigateToPermissions() },
                onOpenTrend = { navController.navigateToEyeTrend() },
            ))
            mineSubScreens(onBack = back, onOpenAccount = openAccount, onOpenCloudSync = { navController.navigateToCloudSync() },
                onEditSubject = { navController.navigateToSubjectEdit(it) })
            systemSettingsScreen(onBack = back)
            subjectEditScreen(onBack = { navController.navigateUp() })
            testModelScreen(
                onBack = { navController.navigateUp() },
                onGazeClick = { navController.navigateToGaze() },
                onBlinkTestClick = { navController.navigateToBlinkTest() },
            )
            gazeScreen(onBack = { navController.navigateUp() })
            blinkTestScreen(onBack = { navController.navigateUp() })
            // 测试开始页缺资料时去「我的」的资料编辑页，保存后返回测试
            dryEyeScreen(onBack = { navController.navigateUp() }, onEditSubject = { navController.navigateToSubjectEdit(it) })
            presbyopiaScreen(onBack = { navController.navigateUp() }, onEditSubject = { navController.navigateToSubjectEdit(it) })
            strabismusScreen(onBack = { navController.navigateUp() }, onEditSubject = { navController.navigateToSubjectEdit(it) })
            colorVisionScreen(onBack = { navController.navigateUp() }, onEditSubject = { navController.navigateToSubjectEdit(it) })
        }
    }
}

/** 浮动底栏：左右留白的半透明胶囊（极淡描边 + 柔和投影），浮在页面底色上；三项都是「图标 + 名称」。 */
@Composable
private fun EyeProjectBottomBar(
    navController: NavHostController,
    currentDestination: NavDestination?,
) {
    val destinations = TopLevelDestination.entries
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        EyeNavigationDock(
            items = destinations.map { EyeNavigationItem(it.label, it.iconRes, it.selectedIconRes) },
            selectedIndex = destinations.indexOfFirst { currentDestination.isInHierarchy(it) },
            onSelect = { navController.navigateToTopLevelDestination(destinations[it]) },
        )
    }
}

private fun NavDestination?.isTopLevelDestination(): Boolean =
    TopLevelDestination.entries.any { isInHierarchy(it) }

private fun NavDestination?.isInHierarchy(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.routeName() } == true

/** 类型安全路由的序列化串即 @Serializable data object 的全限定名。 */
private fun TopLevelDestination.routeName(): String = when (this) {
    TopLevelDestination.HOME -> HomeRoute::class.qualifiedName.orEmpty()
    TopLevelDestination.SERVICES -> ServicesRoute::class.qualifiedName.orEmpty()
    TopLevelDestination.MINE -> MineRoute::class.qualifiedName.orEmpty()
}

private fun NavHostController.navigateToTopLevelDestination(destination: TopLevelDestination) {
    val options = navOptions {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    when (destination) {
        TopLevelDestination.HOME -> navigateToHome(options)
        TopLevelDestination.SERVICES -> navigateToServices(options)
        TopLevelDestination.MINE -> navigateToMine(options)
    }
}
