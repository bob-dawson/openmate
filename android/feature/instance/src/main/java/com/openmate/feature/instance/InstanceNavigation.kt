package com.openmate.feature.instance

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

object InstanceRoutes {
    const val INSTANCE_LIST = "instance_list"
    const val ADD_INSTANCE = "add_instance"
}

fun NavGraphBuilder.instanceScreens(
    navController: NavController,
) {
    composable(InstanceRoutes.INSTANCE_LIST) {
        InstanceListScreen(
            onNavigateToAdd = { navController.navigate(InstanceRoutes.ADD_INSTANCE) },
            onNavigateToSessions = {
                navController.navigate("session_list") {
                    popUpTo(InstanceRoutes.INSTANCE_LIST) { saveState = true }
                }
            },
        )
    }
    composable(InstanceRoutes.ADD_INSTANCE) {
        AddInstanceScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
