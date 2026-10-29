package com.zkyjss.eyeproject.feature.home.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.zkyjss.eyeproject.feature.home.EyeAdviceScreen
import com.zkyjss.eyeproject.feature.home.EyeBrightnessScreen
import com.zkyjss.eyeproject.feature.home.EyeChatScreen
import com.zkyjss.eyeproject.feature.home.EyeTrendScreen
import com.zkyjss.eyeproject.feature.home.HomeNavigation
import com.zkyjss.eyeproject.feature.home.HomeScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

/** 用眼趋势报告（首页「主动服务」与「我的」都能进入）。 */
@Serializable
data object EyeTrendRoute

/** 健康建议：专业咨询建议 + 建议做的自测。 */
@Serializable
data object EyeAdviceRoute

/** 亮度与蓝光。 */
@Serializable
data object EyeBrightnessRoute

/** AI 综合建议对话页；[draft] 为首页点的快捷提问。 */
@Serializable
data class EyeChatRoute(val draft: String? = null)

fun NavController.navigateToHome(navOptions: NavOptions? = null) {
    navigate(HomeRoute, navOptions)
}

fun NavController.navigateToEyeTrend() { navigate(EyeTrendRoute) { launchSingleTop = true } }
fun NavController.navigateToEyeAdvice() { navigate(EyeAdviceRoute) { launchSingleTop = true } }
fun NavController.navigateToEyeBrightness() { navigate(EyeBrightnessRoute) { launchSingleTop = true } }
fun NavController.navigateToEyeChat(draft: String? = null) { navigate(EyeChatRoute(draft)) { launchSingleTop = true } }

/** 首页；跳转（账户、系统设置、测试、资料……）由 app 经 [navigation] 接线。 */
fun NavGraphBuilder.homeScreen(navigation: HomeNavigation) {
    composable<HomeRoute> { HomeScreen(navigation) }
}

/** 首页的二级页面：用眼趋势报告、健康建议、亮度与蓝光、AI 综合建议对话。 */
fun NavGraphBuilder.eyeCareScreens(onBack: () -> Unit, onOpenTest: (String) -> Unit, onOpenAccount: () -> Unit) {
    composable<EyeTrendRoute> { EyeTrendScreen(onBack = onBack) }
    composable<EyeAdviceRoute> { EyeAdviceScreen(onBack = onBack, onOpenTest = onOpenTest) }
    composable<EyeBrightnessRoute> { EyeBrightnessScreen(onBack = onBack) }
    composable<EyeChatRoute> { entry -> EyeChatScreen(onBack = onBack, onOpenAccount = onOpenAccount, draft = entry.toRoute<EyeChatRoute>().draft) }
}
