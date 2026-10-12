package com.zkyjss.eyeproject.ui

import androidx.annotation.DrawableRes
import com.zkyjss.eyeproject.core.designsystem.R

/** 底部导航的顶层目的地（从左到右：首页 / 服务 / 我的）。 */
enum class TopLevelDestination(
    val label: String,
    @DrawableRes val iconRes: Int,
) {
    HOME("首页", R.drawable.ic_nav_home),
    SERVICES("服务", R.drawable.ic_nav_services),
    MINE("我的", R.drawable.ic_nav_mine),
}
