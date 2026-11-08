package com.zkyjss.eyeproject.ui

import androidx.annotation.DrawableRes
import com.zkyjss.eyeproject.core.designsystem.R

/** 底部导航的顶层目的地（从左到右：首页 / 服务 / 我的）。 */
enum class TopLevelDestination(
    val label: String,
    @DrawableRes val iconRes: Int,
    @DrawableRes val selectedIconRes: Int,
) {
    HOME("首页", R.drawable.ic_nav_home, R.drawable.ic_nav_home_filled),
    SERVICES("服务", R.drawable.ic_nav_services, R.drawable.ic_nav_services_filled),
    MINE("我的", R.drawable.ic_nav_mine, R.drawable.ic_nav_mine_filled),
}
