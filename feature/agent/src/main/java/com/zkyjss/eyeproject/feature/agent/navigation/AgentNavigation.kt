package com.zkyjss.eyeproject.feature.agent.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.agent.AgentScreen
import kotlinx.serialization.Serializable

@Serializable
data object AgentRoute

fun NavController.navigateToAgent() {
    navigate(AgentRoute)
}

fun NavGraphBuilder.agentScreen(onBack: () -> Unit) {
    composable<AgentRoute> {
        AgentScreen(onBack = onBack)
    }
}
