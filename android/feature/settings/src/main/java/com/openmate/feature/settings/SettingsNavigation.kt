package com.openmate.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

object SettingsRoutes {
    const val SETTINGS = "settings"
}

fun NavGraphBuilder.settingsScreen(
    navController: NavController,
) {
    composable(SettingsRoutes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
