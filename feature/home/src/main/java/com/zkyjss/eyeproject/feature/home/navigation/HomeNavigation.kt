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

fun NavGraphBuilder.homeScreen(onNavigateToTestModel: () -> Unit) {
    composable<HomeRoute> {
        HomeScreen(onNavigateToTestModel = onNavigateToTestModel)
    }
}
