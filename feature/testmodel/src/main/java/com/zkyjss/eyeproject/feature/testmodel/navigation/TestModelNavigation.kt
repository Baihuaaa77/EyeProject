package com.zkyjss.eyeproject.feature.testmodel.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.testmodel.TestModelScreen
import kotlinx.serialization.Serializable

@Serializable
data object TestModelRoute

fun NavController.navigateToTestModel() {
    navigate(TestModelRoute)
}

fun NavGraphBuilder.testModelScreen(
    onBack: () -> Unit,
    onGazeClick: () -> Unit,
    onBlinkTestClick: () -> Unit,
) {
    composable<TestModelRoute> {
        TestModelScreen(
            onBack = onBack,
            onGazeClick = onGazeClick,
            onBlinkTestClick = onBlinkTestClick,
        )
    }
}
