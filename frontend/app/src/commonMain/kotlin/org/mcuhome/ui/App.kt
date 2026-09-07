// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import org.mcuhome.ui.config.ConfigsPage
import org.mcuhome.ui.device.DevicePage
import org.mcuhome.ui.device.DevicesPage
import org.mcuhome.ui.device.LocalRailSession
import org.mcuhome.ui.device.RailSession
import org.mcuhome.ui.download.FileDownloader
import org.mcuhome.ui.download.LocalFileDownloader
import org.mcuhome.ui.job.JobsChip
import org.mcuhome.ui.job.rememberJobList
import org.mcuhome.ui.panel.LocalPanelSession
import org.mcuhome.ui.panel.PanelSession
import org.mcuhome.ui.project.ProjectPage
import org.mcuhome.ui.secret.SecretsPage
import org.mcuhome.ui.shell.ConfigRoute
import org.mcuhome.ui.shell.Destination
import org.mcuhome.ui.shell.DeviceRoute
import org.mcuhome.ui.shell.LocalKeyboardInset
import org.mcuhome.ui.shell.LocalNavigationGuard
import org.mcuhome.ui.shell.LocalWindowSize
import org.mcuhome.ui.shell.NavigationGuard
import org.mcuhome.ui.shell.NavigationMenu
import org.mcuhome.ui.shell.TopBar
import org.mcuhome.ui.shell.TopBarHeight
import org.mcuhome.ui.shell.WindowSize
import org.mcuhome.ui.shell.rememberKeyboardInset
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
        LocalRailSession provides remember { RailSession() },
        LocalNavigationGuard provides remember { NavigationGuard() },
    ) {
        // The window is measured once, here, and every layout decision
        // below reads the class it falls into rather than a width of its
        // own. What the on-screen keyboard covers is measured in the same
        // place and for the same reason.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowSize = WindowSize(maxWidth, maxHeight)
            CompositionLocalProvider(
                LocalWindowSize provides windowSize,
                LocalKeyboardInset provides rememberKeyboardInset(enabled = windowSize.compact),
            ) {
                AppContent(onNavHostReady)
            }
        }
    }
}

@Composable
private fun AppContent(onNavHostReady: suspend (NavHostController) -> Unit) {
    val api = LocalMcuHomeApi.current
    val window = LocalWindowSize.current
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    var navigationMenu by remember { mutableStateOf(false) }
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
        val openConfig: (String) -> Unit = { file ->
            guard.navigate {
                navController.navigate(ConfigRoute(file)) {
                    popUpTo(Destination.start.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        val openDevices: () -> Unit = {
            guard.navigate {
                navController.navigate(Destination.Devices.route) {
                    popUpTo(Destination.start.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        val navigate: (Destination) -> Unit = { destination ->
            navigationMenu = false
            guard.navigate {
                navController.navigate(destination.route) {
                    popUpTo(Destination.start.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        val jobsChip: @Composable () -> Unit = { JobsChip(jobs = jobs, onOpenDevice = openDevice) }
        // A phone's device page carries its own header — the device's
        // name, its jobs and its actions — and the bar above it would
        // repeat what that header already says on a screen that has no
        // room for either. Every other screen keeps the bar.
        val ownHeader = window.compact && currentRoute?.startsWith("${Destination.Devices.route}/") == true

        Box(Modifier.fillMaxSize().background(MCUHomeTheme.colors.background)) {
            Column(Modifier.fillMaxSize()) {
                val connection by api.connection.collectAsState()
                if (!ownHeader) {
                    TopBar(
                        projectName = serverInfo?.projectName.orEmpty(),
                        current = currentDestination,
                        onNavigate = navigate,
                        connected = connection == ConnectionState.Connected,
                        onOpenMenu = { navigationMenu = !navigationMenu },
                        jobsChip = jobsChip,
                    )
                }

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
                            jobsChip = jobsChip,
                        )
                    }
                    composable(Destination.Configs.route) {
                        ConfigsPage(fileName = null, onOpenConfig = openConfig)
                    }
                    composable<ConfigRoute> { entry ->
                        ConfigsPage(
                            fileName = entry.toRoute<ConfigRoute>().file,
                            onOpenConfig = openConfig,
                            onCloseConfig = { navigate(Destination.Configs) },
                        )
                    }
                    composable(Destination.Secrets.route) {
                        SecretsPage()
                    }
                    composable(Destination.Project.route) {
                        ProjectPage()
                    }
                }
            }

            if (navigationMenu && !ownHeader) {
                NavigationMenu(
                    current = currentDestination,
                    onNavigate = navigate,
                    onDismiss = { navigationMenu = false },
                    modifier = Modifier.padding(top = TopBarHeight),
                )
            }
        }

        LaunchNavHostReady(navController, onNavHostReady)
    }
}

@Composable
private fun LaunchNavHostReady(navController: NavHostController, onNavHostReady: suspend (NavHostController) -> Unit) {
    LaunchedEffect(navController) { onNavHostReady(navController) }
}
