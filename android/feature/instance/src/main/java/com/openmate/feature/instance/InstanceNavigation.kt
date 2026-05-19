package com.openmate.feature.instance

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.hilt.navigation.compose.hiltViewModel

object InstanceRoutes {
    const val INSTANCE_LIST = "instance_list"
    const val ADD_INSTANCE = "add_instance"
    const val QR_SCAN = "qr_scan"
    const val EDIT_INSTANCE = "edit_instance/{profileId}"
    fun editInstance(profileId: String) = "edit_instance/$profileId"
}

fun NavGraphBuilder.instanceScreens(
    navController: NavController,
) {
    composable(InstanceRoutes.INSTANCE_LIST) {
        InstanceListScreen(
            onNavigateToAdd = { navController.navigate(InstanceRoutes.ADD_INSTANCE) },
            onNavigateToEdit = { profileId -> navController.navigate(InstanceRoutes.editInstance(profileId)) },
            onNavigateToQrScan = { navController.navigate(InstanceRoutes.QR_SCAN) },
            onNavigateToSessions = {
                navController.navigate("workspace_list") {
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
    composable(InstanceRoutes.QR_SCAN) {
        val scanViewModel: QrScanViewModel = hiltViewModel()
        QrScanScreen(
            onBack = { navController.popBackStack() },
            onScanComplete = { name, address, port, scanToken ->
                scanViewModel.handleScanComplete(name, address, port)
                navController.popBackStack()
            },
            viewModel = scanViewModel,
        )
    }
    composable(InstanceRoutes.EDIT_INSTANCE) { backStackEntry ->
        val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
        AddInstanceScreen(
            onBack = { navController.popBackStack() },
            editProfileId = profileId,
        )
    }
}
