package com.openmate.feature.session

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openmate.feature.session.component.WorkspaceBrowserScreen
import java.net.URLDecoder
import java.net.URLEncoder

object SessionRoutes {
    const val WORKSPACE_LIST = "workspace_list"
    const val SESSION_LIST = "session_list"
    const val SESSION_DETAIL = "session_detail"
    const val SUBTASK_DETAIL = "subtask_detail"
    const val WORKSPACE_BROWSER = "workspace_browser"
}

fun NavGraphBuilder.sessionScreens(
    navController: NavController,
) {
    composable(SessionRoutes.WORKSPACE_LIST) {
        WorkspaceListScreen(
            onNavigateToWorkspace = { directory ->
                val encoded = URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.SESSION_LIST}/$encoded")
            },
            onNavigateToDetail = { id ->
                navController.navigate("${SessionRoutes.SESSION_DETAIL}/$id")
            },
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
            onNavigateToSubtask = { subtaskSessionID, title ->
                navController.navigate("${SessionRoutes.SUBTASK_DETAIL}/$subtaskSessionID?title=${URLEncoder.encode(title, "UTF-8")}")
            },
            onNavigateToBrowser = { directory ->
                val encoded = URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.WORKSPACE_BROWSER}/$encoded")
            },
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = "${SessionRoutes.SUBTASK_DETAIL}/{subtaskSessionID}?title={title}",
        arguments = listOf(
            navArgument("subtaskSessionID") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "Subtask" },
        ),
    ) { backStackEntry ->
        val subtaskSessionID = backStackEntry.arguments?.getString("subtaskSessionID") ?: return@composable
        val title = URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "Subtask", "UTF-8")
        SubtaskDetailScreen(
            subtaskSessionID = subtaskSessionID,
            title = title,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = "${SessionRoutes.WORKSPACE_BROWSER}/{directory}",
        arguments = listOf(navArgument("directory") { type = NavType.StringType }),
    ) { backStackEntry ->
        val encoded = backStackEntry.arguments?.getString("directory") ?: return@composable
        val directory = URLDecoder.decode(encoded, "UTF-8")
        WorkspaceBrowserScreen(
            initialDirectory = directory,
            onBack = { navController.popBackStack() },
        )
    }
}
