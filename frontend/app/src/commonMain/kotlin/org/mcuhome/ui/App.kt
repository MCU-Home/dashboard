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
import androidx.navigation.toRoute
import org.mcuhome.ui.api.ConnectionState
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.McuHomeApi
import org.mcuhome.ui.api.ServerInfo
import org.mcuhome.ui.device.DevicePage
import org.mcuhome.ui.device.DevicesPage
import org.mcuhome.ui.download.FileDownloader
import org.mcuhome.ui.download.LocalFileDownloader
import org.mcuhome.ui.job.JobsChip
import org.mcuhome.ui.job.rememberJobList
import org.mcuhome.ui.page.PlaceholderPage
import org.mcuhome.ui.panel.LocalPanelSession
import org.mcuhome.ui.panel.PanelSession
import org.mcuhome.ui.shell.Destination
import org.mcuhome.ui.shell.DeviceRoute
import org.mcuhome.ui.shell.LocalNavigationGuard
import org.mcuhome.ui.shell.NavigationGuard
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
 * [downloader] is the second: how a file the server offers reaches the
 * user is the one thing a browser and a desktop application genuinely do
 * differently, and it is installed here for the same reason.
 *
 * [onNavHostReady] hands the freshly created controller to the entry
 * point of the platform. The browser entry point uses it to tie the
 * navigation to the address bar and the back button; a future desktop or
 * mobile entry point needs nothing there and passes its own callback or
 * none at all.
 */
@Composable
fun App(
    api: McuHomeApi,
    onNavHostReady: suspend (NavHostController) -> Unit = {},
    downloader: FileDownloader = FileDownloader { },
) {
    CompositionLocalProvider(
        LocalMcuHomeApi provides api,
        LocalFileDownloader provides downloader,
        LocalPanelSession provides remember { PanelSession() },
        LocalNavigationGuard provides remember { NavigationGuard() },
    ) {
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
        val currentDestination = Destination.entries.firstOrNull { destination ->
            currentRoute == destination.route || currentRoute?.startsWith("${destination.route}/") == true
        }
        val jobs by rememberJobList(api)
        val guard = LocalNavigationGuard.current
        val openDevice: (String) -> Unit = { name ->
            guard.navigate { navController.navigate(DeviceRoute(name)) }
        }
        val openDevices: () -> Unit = {
            guard.navigate {
                navController.navigate(Destination.Devices.route) {
                    popUpTo(Destination.start.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        Column(Modifier.fillMaxSize().background(MCUHomeTheme.colors.background)) {
            val connection by api.connection.collectAsState()
            TopBar(
                projectName = serverInfo?.projectName.orEmpty(),
                current = currentDestination,
                onNavigate = { destination ->
                    guard.navigate {
                        navController.navigate(destination.route) {
                            popUpTo(Destination.start.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                connected = connection == ConnectionState.Connected,
                jobsChip = { JobsChip(jobs = jobs, onOpenDevice = openDevice) },
            )

            NavHost(
                navController = navController,
                startDestination = Destination.start.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(Destination.Devices.route) {
                    DevicesPage(onOpenDevice = openDevice)
                }
                composable<DeviceRoute> { entry ->
                    val route = entry.toRoute<DeviceRoute>()
                    DevicePage(
                        name = route.name,
                        onOpenDevices = openDevices,
                        onOpenDevice = openDevice,
                    )
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
private fun LaunchNavHostReady(navController: NavHostController, onNavHostReady: suspend (NavHostController) -> Unit) {
    LaunchedEffect(navController) { onNavHostReady(navController) }
}
