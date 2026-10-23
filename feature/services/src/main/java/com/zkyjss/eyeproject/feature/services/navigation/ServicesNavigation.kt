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
    onOpenSettings: () -> Unit = {},
) {
    composable<ServicesRoute> {
        ServicesScreen(
            onDryEyeClick = onDryEyeClick,
            onPresbyopiaClick = onPresbyopiaClick,
            onStrabismusClick = onStrabismusClick,
            onOpenSettings = onOpenSettings,
        )
    }
}

/** [onEditSubject]：去「我的」补充或新建被测者资料（app 负责跳转，feature 之间不直接依赖）。 */
fun NavGraphBuilder.dryEyeScreen(onBack: () -> Unit, onEditSubject: (String?) -> Unit) {
    composable<DryEyeRoute> {
        DryEyeScreen(onBack = onBack, onEditSubject = onEditSubject)
    }
}

fun NavGraphBuilder.presbyopiaScreen(onBack: () -> Unit, onEditSubject: (String?) -> Unit) {
    composable<PresbyopiaRoute> {
        PresbyopiaScreen(onBack = onBack, onEditSubject = onEditSubject)
    }
}

fun NavGraphBuilder.strabismusScreen(onBack: () -> Unit, onEditSubject: (String?) -> Unit) {
    composable<StrabismusRoute> {
        StrabismusScreen(onBack = onBack, onEditSubject = onEditSubject)
    }
}
