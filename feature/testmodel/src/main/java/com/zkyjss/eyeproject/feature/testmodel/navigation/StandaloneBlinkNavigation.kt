package com.zkyjss.eyeproject.feature.testmodel.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.testmodel.standalone.StandaloneBlinkScreen
import kotlinx.serialization.Serializable

@Serializable
data object StandaloneBlinkRoute

fun NavController.navigateToStandaloneBlink() {
    navigate(StandaloneBlinkRoute)
}

fun NavGraphBuilder.standaloneBlinkScreen(onBack: () -> Unit) {
    composable<StandaloneBlinkRoute> {
        StandaloneBlinkScreen(onBack = onBack)
    }
}
