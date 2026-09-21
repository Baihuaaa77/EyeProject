package com.zkyjss.eyeproject.feature.services.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.services.DryEyeScreen
import com.zkyjss.eyeproject.feature.services.PresbyopiaScreen
import com.zkyjss.eyeproject.feature.services.ServicesScreen
import com.zkyjss.eyeproject.feature.services.StrabismusScreen
import kotlinx.serialization.Serializable

@Serializable
data object ServicesRoute

@Serializable
data object DryEyeRoute

@Serializable
data object PresbyopiaRoute

@Serializable
data object StrabismusRoute

fun NavController.navigateToServices(navOptions: NavOptions? = null) {
    navigate(ServicesRoute, navOptions)
}

fun NavController.navigateToDryEye() {
    navigate(DryEyeRoute)
}

fun NavController.navigateToPresbyopia() {
    navigate(PresbyopiaRoute)
}

fun NavController.navigateToStrabismus() {
    navigate(StrabismusRoute)
}

fun NavGraphBuilder.servicesScreen(
    onDryEyeClick: () -> Unit,
    onPresbyopiaClick: () -> Unit,
    onStrabismusClick: () -> Unit,
) {
    composable<ServicesRoute> {
        ServicesScreen(
            onDryEyeClick = onDryEyeClick,
            onPresbyopiaClick = onPresbyopiaClick,
            onStrabismusClick = onStrabismusClick,
        )
    }
}

fun NavGraphBuilder.dryEyeScreen(onBack: () -> Unit) {
    composable<DryEyeRoute> {
        DryEyeScreen(onBack = onBack)
    }
}

fun NavGraphBuilder.presbyopiaScreen(onBack: () -> Unit) {
    composable<PresbyopiaRoute> {
        PresbyopiaScreen(onBack = onBack)
    }
}

fun NavGraphBuilder.strabismusScreen(onBack: () -> Unit) {
    composable<StrabismusRoute> {
        StrabismusScreen(onBack = onBack)
    }
}
