package com.openmate.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openmate.feature.instance.InstanceRoutes
import com.openmate.feature.instance.instanceScreens
import com.openmate.feature.session.SessionRoutes
import com.openmate.feature.session.sessionScreens
import com.openmate.feature.settings.settingsScreen

@Composable
fun OpenMateNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = InstanceRoutes.INSTANCE_LIST,
    ) {
        instanceScreens(navController)
        sessionScreens(navController)
        settingsScreen(navController)
    }
}
