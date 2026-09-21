package com.zkyjss.eyeproject.feature.mine.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.mine.MineScreen
import kotlinx.serialization.Serializable

@Serializable
data object MineRoute

fun NavController.navigateToMine(navOptions: NavOptions? = null) {
    navigate(MineRoute, navOptions)
}

fun NavGraphBuilder.mineScreen() {
    composable<MineRoute> {
        MineScreen()
    }
}
