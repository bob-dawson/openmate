package com.openmate.feature.session

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import java.net.URLDecoder

object SessionRoutes {
    const val WORKSPACE_LIST = "workspace_list"
    const val SESSION_LIST = "session_list"
    const val SESSION_DETAIL = "session_detail"
}

fun NavGraphBuilder.sessionScreens(
    navController: NavController,
) {
    composable(SessionRoutes.WORKSPACE_LIST) {
        WorkspaceListScreen(
            onNavigateToWorkspace = { directory ->
                val encoded = java.net.URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.SESSION_LIST}/$encoded")
            },
            onNavigateToSettings = { navController.navigate("settings") },
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = "${SessionRoutes.SESSION_LIST}/{directory}",
        arguments = listOf(navArgument("directory") { type = NavType.StringType }),
    ) { backStackEntry ->
        val encoded = backStackEntry.arguments?.getString("directory") ?: return@composable
        val directory = URLDecoder.decode(encoded, "UTF-8")
        SessionListScreen(
            directory = directory,
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
