package com.openmate.feature.session

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

object SessionRoutes {
    const val SESSION_LIST = "session_list"
    const val SESSION_DETAIL = "session_detail"
}

fun NavGraphBuilder.sessionScreens(
    navController: NavController,
) {
    composable(SessionRoutes.SESSION_LIST) {
        SessionListScreen(
            onNavigateToDetail = { id ->
                navController.navigate("${SessionRoutes.SESSION_DETAIL}/$id")
            },
            onNavigateToSettings = { navController.navigate("settings") },
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = "${SessionRoutes.SESSION_DETAIL}/{sessionID}",
        arguments = listOf(navArgument("sessionID") { type = NavType.StringType }),
    ) { backStackEntry ->
        val sessionID = backStackEntry.arguments?.getString("sessionID") ?: return@composable
        SessionDetailScreen(
            sessionID = sessionID,
            onBack = { navController.popBackStack() },
        )
    }
}
