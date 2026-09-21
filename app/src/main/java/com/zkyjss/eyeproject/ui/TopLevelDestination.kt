package com.zkyjss.eyeproject.ui

/** 底部导航的顶层目的地（从左到右：首页 / 服务 / 我的）。 */
enum class TopLevelDestination(
    val label: String,
) {
    HOME("首页"),
    SERVICES("服务"),
    MINE("我的"),
}
