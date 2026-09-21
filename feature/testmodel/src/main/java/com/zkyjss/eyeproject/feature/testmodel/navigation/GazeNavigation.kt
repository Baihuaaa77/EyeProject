package com.zkyjss.eyeproject.feature.testmodel.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.testmodel.gaze.GazeScreen
import kotlinx.serialization.Serializable

@Serializable
data object GazeRoute

fun NavController.navigateToGaze() {
    navigate(GazeRoute)
}

fun NavGraphBuilder.gazeScreen(onBack: () -> Unit) {
    composable<GazeRoute> {
        GazeScreen(onBack = onBack)
    }
}
