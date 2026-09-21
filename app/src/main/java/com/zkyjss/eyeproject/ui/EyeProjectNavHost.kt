package com.zkyjss.eyeproject.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.util.borderTop
import com.zkyjss.eyeproject.core.designsystem.util.clickableNoRipple
import com.zkyjss.eyeproject.feature.agent.navigation.agentScreen
import com.zkyjss.eyeproject.feature.agent.navigation.navigateToAgent
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
        floatingActionButton = {
            if (currentDestination.isTopLevelDestination()) {
                Image(
                    painter = painterResource(R.drawable.agent),
                    contentDescription = "智能体",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
                        .background(colors.white, shape = CircleShape)
                        .clickableNoRipple(
                            onClick = {navController.navigateToAgent()}
                        ),
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = FatigueRoute,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
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
            .background(colors.appBackground)
            .navigationBarsPadding()
            .height(64.dp)
            .borderTop(color = Color(0xFFFFFFFF)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentDestination.isInHierarchy(destination)
            Text(
                text = destination.label,
                fontSize = 16.sp,
                color = if (selected) colors.primary else colors.textSecondary,
                modifier = Modifier
                    .clickableNoRipple { navController.navigateToTopLevelDestination(destination) }
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
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
