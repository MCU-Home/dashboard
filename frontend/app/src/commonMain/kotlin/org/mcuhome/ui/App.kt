// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.mcuhome.ui.api.ConnectionState
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.McuHomeApi
import org.mcuhome.ui.api.ServerInfo
import org.mcuhome.ui.page.PlaceholderPage
import org.mcuhome.ui.page.SpikePage
import org.mcuhome.ui.shell.Destination
import org.mcuhome.ui.shell.SPIKE_ROUTE
import org.mcuhome.ui.shell.TopBar
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The application: the theme, the shell around every screen, and the
 * navigation graph.
 *
 * [api] is the one thing the platform decides for the application: today
 * every entry point hands in the in-memory mock, and the client that talks
 * to the back end replaces it without a screen changing. It is put into
 * the composition once, here, and read from `LocalMcuHomeApi` wherever it
 * is needed.
 *
 * [onNavHostReady] hands the freshly created controller to the entry
 * point of the platform. The browser entry point uses it to tie the
 * navigation to the address bar and the back button; a future desktop or
 * mobile entry point needs nothing there and passes its own callback or
 * none at all.
 */
@Composable
fun App(api: McuHomeApi, onNavHostReady: suspend (NavHostController) -> Unit = {}) {
    CompositionLocalProvider(LocalMcuHomeApi provides api) {
        AppContent(onNavHostReady)
    }
}

@Composable
private fun AppContent(onNavHostReady: suspend (NavHostController) -> Unit) {
    val api = LocalMcuHomeApi.current
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    LaunchedEffect(api) { serverInfo = api.server.info() }
    MCUHomeTheme {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val currentDestination = Destination.entries.firstOrNull { it.route == currentRoute }

        Column(Modifier.fillMaxSize().background(MCUHomeTheme.colors.background)) {
            val connection by api.connection.collectAsState()
            TopBar(
                projectName = serverInfo?.projectName.orEmpty(),
                current = currentDestination,
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(Destination.start.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                runningJobs = 1,
                connected = connection == ConnectionState.Connected,
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
                composable(SPIKE_ROUTE) { SpikePage() }
            }
        }

        LaunchNavHostReady(navController, onNavHostReady)
    }
}

@Composable
private fun LaunchNavHostReady(navController: NavHostController, onNavHostReady: suspend (NavHostController) -> Unit) {
    LaunchedEffect(navController) { onNavHostReady(navController) }
}
