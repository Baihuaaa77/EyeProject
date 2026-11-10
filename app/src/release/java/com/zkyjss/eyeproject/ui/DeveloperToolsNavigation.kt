package com.zkyjss.eyeproject.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** 正式变体不链接模型实验模块，也不注册其路由。 */
internal fun developerToolsAction(nav: NavHostController): (() -> Unit)? = null
internal fun NavGraphBuilder.developerToolsScreens(nav: NavHostController) = Unit
