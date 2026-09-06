// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.mcuhome.ui.page.PlaceholderPage
import org.mcuhome.ui.shell.Destination
import org.mcuhome.ui.shell.TopBar
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The application: the theme, the shell around every screen, and the
 * navigation graph.
 *
 * [onNavHostReady] hands the freshly created controller to the entry
 * point of the platform. The browser entry point uses it to tie the
 * navigation to the address bar and the back button; a future desktop or
 * mobile entry point needs nothing there and passes its own callback or
 * none at all.
 */
@Composable
fun App(onNavHostReady: suspend (NavHostController) -> Unit = {}) {
    MCUHomeTheme {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val currentDestination = Destination.entries.firstOrNull { it.route == currentRoute }

        Column(Modifier.fillMaxSize().background(MCUHomeTheme.colors.background)) {
            TopBar(
                projectName = "my-home",
                current = currentDestination,
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(Destination.start.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                runningJobs = 1,
                connected = true,
            )

            NavHost(
                navController = navController,
                startDestination = Destination.start.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(Destination.Devices.route) {
                    PlaceholderPage("Devices", "The device table, filters and the New device action.")
                }
                composable(Destination.Configs.route) {
                    PlaceholderPage("Configs", "The shared configuration files of this project and their users.")
                }
                composable(Destination.Secrets.route) {
                    PlaceholderPage("Secrets", "The four secret scopes: project, devices, build server, firmware key.")
                }
                composable(Destination.Project.route) {
                    PlaceholderPage("Project", "Project options, the project file, boards and the doctor report.")
                }
            }
        }

        LaunchNavHostReady(navController, onNavHostReady)
    }
}

@Composable
private fun LaunchNavHostReady(
    navController: NavHostController,
    onNavHostReady: suspend (NavHostController) -> Unit,
) {
    LaunchedEffect(navController) { onNavHostReady(navController) }
}
