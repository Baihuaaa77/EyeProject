package com.zkyjss.eyeproject.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.zkyjss.eyeproject.feature.testmodel.navigation.blinkTestScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.gazeScreen
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToBlinkTest
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToGaze
import com.zkyjss.eyeproject.feature.testmodel.navigation.navigateToTestModel
import com.zkyjss.eyeproject.feature.testmodel.navigation.testModelScreen

/** 实验模块及其导航只参与调试变体编译。 */
internal fun developerToolsAction(nav: NavHostController): (() -> Unit)? = { nav.navigateToTestModel() }
internal fun NavGraphBuilder.developerToolsScreens(nav: NavHostController) {
    testModelScreen(onBack = { nav.navigateUp() }, onGazeClick = { nav.navigateToGaze() }, onBlinkTestClick = { nav.navigateToBlinkTest() })
    gazeScreen(onBack = { nav.navigateUp() })
    blinkTestScreen(onBack = { nav.navigateUp() })
}
