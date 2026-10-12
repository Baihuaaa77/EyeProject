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
import com.zkyjss.eyeproject.feature.fatigue.navigation.FatigueRoute
import com.zkyjss.eyeproject.feature.fatigue.navigation.fatigueScreen
import com.zkyjss.eyeproject.feature.fatigue.navigation.navigateToFatigue
import com.zkyjss.eyeproject.feature.mine.navigation.MineRoute
import com.zkyjss.eyeproject.feature.services.navigation.ServicesRoute
import com.zkyjss.eyeproject.feature.services.navigation.dryEyeScreen
import com.zkyjss.eyeproject.feature.mine.navigation.mineScreen
import com.zkyjss.eyeproject.feature.mine.navigation.navigateToMine
import com.zkyjss.eyeproject.feature.services.navigation.navigateToDryEye
import com.zkyjss.eyeproject.feature.services.navigation.navigateToPresbyopia
import com.zkyjss.eyeproject.feature.services.navigation.navigateToServices
import com.zkyjss.eyeproject.feature.services.navigation.navigateToStrabismus
import com.zkyjss.eyeproject.feature.services.navigation.presbyopiaScreen
import com.zkyjss.eyeproject.feature.services.navigation.servicesScreen
import com.zkyjss.eyeproject.feature.services.navigation.strabismusScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.blinkTestScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.gazeScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToBlinkTest
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToGaze
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToStandaloneBlink
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToTestModel
import com.zkyjss.eyeproject.feature.testmodel.navigation.standaloneBlinkScreen
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
            startDestination = FatigueRoute,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            // 品牌过渡：淡入 + 轻微上浮，退出只淡出，避免页面「飞来飞去」
            enterTransition = { fadeIn(tween(EyeMotion.MEDIUM)) + slideInVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) { it / 24 } },
            exitTransition = { fadeOut(tween(EyeMotion.SHORT)) },
            popEnterTransition = { fadeIn(tween(EyeMotion.MEDIUM)) },
            popExitTransition = { fadeOut(tween(EyeMotion.SHORT)) + slideOutVertically(tween(EyeMotion.MEDIUM)) { it / 24 } },
        ) {
            fatigueScreen(
                onNavigateToTestModel = { navController.navigateToTestModel() }
            )
            servicesScreen(
                onDryEyeClick = { navController.navigateToDryEye() },
                onPresbyopiaClick = { navController.navigateToPresbyopia() },
                onStrabismusClick = { navController.navigateToStrabismus() },
            )
            mineScreen()
            agentScreen(onBack = { navController.navigateUp() })
            testModelScreen(
                onBack = { navController.navigateUp() },
                onGazeClick = { navController.navigateToGaze() },
                onBlinkTestClick = { navController.navigateToBlinkTest() },
                onStandaloneClick = { navController.navigateToStandaloneBlink() }
            )
            gazeScreen(onBack = { navController.navigateUp() })
            blinkTestScreen(onBack = { navController.navigateUp() })
            standaloneBlinkScreen(onBack = { navController.navigateUp() })
            dryEyeScreen(onBack = { navController.navigateUp() })
            presbyopiaScreen(onBack = { navController.navigateUp() })
            strabismusScreen(onBack = { navController.navigateUp() })
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
    TopLevelDestination.HOME -> FatigueRoute::class.qualifiedName.orEmpty()
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
        TopLevelDestination.HOME -> navigateToFatigue(options)
        TopLevelDestination.SERVICES -> navigateToServices(options)
        TopLevelDestination.MINE -> navigateToMine(options)
    }
}
