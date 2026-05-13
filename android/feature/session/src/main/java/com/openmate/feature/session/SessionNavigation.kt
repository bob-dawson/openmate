package com.openmate.feature.session

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openmate.feature.session.component.WorkspaceBrowserScreen
import com.openmate.feature.settings.LocalFileManagerScreen
import java.net.URLDecoder
import java.net.URLEncoder

object SessionRoutes {
    const val WORKSPACE_LIST = "workspace_list"
    const val SESSION_LIST = "session_list"
    const val SESSION_DETAIL = "session_detail"
    const val SUBTASK_DETAIL = "subtask_detail"
    const val WORKSPACE_BROWSER = "workspace_browser"
    const val LOCAL_FILE_MANAGER = "local_file_manager"
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
            onNavigateToLocalFileManager = {
                navController.navigate(SessionRoutes.LOCAL_FILE_MANAGER)
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
            onNavigateToSubtask = { subtaskSessionID, _ ->
                navController.navigate("${SessionRoutes.SUBTASK_DETAIL}/$subtaskSessionID")
            },
            onNavigateToBrowser = { directory ->
                val encoded = URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.WORKSPACE_BROWSER}/$encoded")
            },
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = "${SessionRoutes.SUBTASK_DETAIL}/{subtaskSessionID}",
        arguments = listOf(
            navArgument("subtaskSessionID") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val subtaskSessionID = backStackEntry.arguments?.getString("subtaskSessionID") ?: return@composable
        SessionDetailScreen(
            sessionID = subtaskSessionID,
            onNavigateToSubtask = { nestedId, _ ->
                navController.navigate("${SessionRoutes.SUBTASK_DETAIL}/$nestedId")
            },
            onNavigateToBrowser = { directory ->
                val encoded = URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.WORKSPACE_BROWSER}/$encoded")
            },
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
    composable(SessionRoutes.LOCAL_FILE_MANAGER) {
        LocalFileManagerScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
