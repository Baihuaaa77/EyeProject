package com.zkyjss.eyeproject.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.ui.component.EyeIcon
import com.zkyjss.eyeproject.core.ui.component.EyeText
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.zkyjss.eyeproject.core.designsystem.util.borderTop
import com.zkyjss.eyeproject.feature.agent.navigation.agentScreen
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
    // 三个顶层页右上角：账户（人像）与系统设置（齿轮），都是完整页面
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
                onNavigateToTestModel = { navController.navigateToTestModel() },
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
            agentScreen(onBack = { navController.navigateUp() })
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

@Composable
private fun EyeProjectBottomBar(
    navController: NavHostController,
    currentDestination: NavDestination?,
) {
    val colors = LocalEyeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panelBackground)
            .borderTop(color = colors.borderSubtle)
            .navigationBarsPadding()
            .height(68.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentDestination.isInHierarchy(destination)
            BottomBarItem(
                destination = destination,
                selected = selected,
                onClick = { navController.navigateToTopLevelDestination(destination) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 选中项：图标后方的浅主色胶囊以弹簧展开，图标/文字同步变色并轻微放大。 */
@Composable
private fun BottomBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val pillWidth by animateDpAsState(if (selected) 56.dp else 0.dp, EyeMotion.dpSpring, label = "导航胶囊")
    val tint by animateColorAsState(if (selected) colors.primary else colors.textSecondary, tween(EyeMotion.MEDIUM), label = "导航色")
    val iconScale by animateFloatAsState(if (selected) 1.08f else 1f, EyeMotion.settleSpring(), label = "导航图标")
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(30.dp).width(56.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.height(30.dp).width(pillWidth).clip(EyeShapes.chip).background(colors.primarySoft))
            EyeIcon(
                painter = painterResource(destination.iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            )
        }
        Spacer(Modifier.height(4.dp))
        EyeText(destination.label, style = typography.chip, color = tint)
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
