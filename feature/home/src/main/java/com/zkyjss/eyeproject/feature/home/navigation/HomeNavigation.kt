package com.zkyjss.eyeproject.feature.home.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.home.HomeScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

fun NavController.navigateToHome(navOptions: NavOptions? = null) {
    navigate(HomeRoute, navOptions)
}

/** [onOpenSettings]：右上角齿轮打开「系统设置」（app 持有设置面板，三个顶层页共用）。 */
fun NavGraphBuilder.homeScreen(onNavigateToTestModel: () -> Unit, onOpenSettings: () -> Unit = {}) {
    composable<HomeRoute> {
        HomeScreen(onNavigateToTestModel = onNavigateToTestModel, onOpenSettings = onOpenSettings)
    }
}
