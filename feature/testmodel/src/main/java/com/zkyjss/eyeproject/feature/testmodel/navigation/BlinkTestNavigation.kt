package com.zkyjss.eyeproject.feature.testmodel.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.testmodel.blink.BlinkTestScreen
import kotlinx.serialization.Serializable

@Serializable
data object BlinkTestRoute

fun NavController.navigateToBlinkTest() {
    navigate(BlinkTestRoute)
}

fun NavGraphBuilder.blinkTestScreen(onBack: () -> Unit) {
    composable<BlinkTestRoute> {
        BlinkTestScreen(onBack = onBack)
    }
}
