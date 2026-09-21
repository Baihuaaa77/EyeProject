package com.zkyjss.eyeproject.feature.fatigue.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.fatigue.FatigueScreen
import kotlinx.serialization.Serializable

@Serializable
data object FatigueRoute

fun NavController.navigateToFatigue(navOptions: NavOptions? = null) {
    navigate(FatigueRoute, navOptions)
}

fun NavGraphBuilder.fatigueScreen(onNavigateToTestModel: () -> Unit) {
    composable<FatigueRoute> {
        FatigueScreen(onNavigateToTestModel = onNavigateToTestModel)
    }
}
