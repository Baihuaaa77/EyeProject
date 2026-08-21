package com.zkyjss.eyeproject.core.designsystem.util

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 界面过度
 */
object ScreenSlideTransition {
    val enterTransition = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(durationMillis = 400)
    )

    val exitTransition = slideOutHorizontally(
        targetOffsetX = { -it },
        animationSpec = tween(durationMillis = 400)
    )

    val popEnterTransition = slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = tween(durationMillis = 400)
    )

    val popExitTransition = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(durationMillis = 400)
    )
}