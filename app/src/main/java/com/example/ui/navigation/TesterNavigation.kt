package com.example.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.di.TesterAppContainer
import com.example.ui.components.ModeChipButton
import com.example.ui.components.ModeConfigBottomSheet
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.download.DirectDownloadScreen
import com.example.ui.download.DirectDownloadViewModel
import com.example.ui.handoff.HandoffScreen
import com.example.ui.handoff.HandoffViewModel
import com.example.ui.search.SearchScreen
import com.example.ui.search.SearchViewModel
import com.example.ui.theme.HairlineBorder
import com.example.ui.worker.WorkerMonitorScreen
import com.example.ui.worker.WorkerMonitorViewModel

object TesterRoutes {
    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_SEARCH = "search"
    const val ROUTE_HANDOFF = "handoff"
    const val ROUTE_WORKER = "worker"
    const val ROUTE_DOWNLOAD = "download"
}

enum class TesterDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD(
        route = TesterRoutes.ROUTE_DASHBOARD,
        label = "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard
    ),
    SEARCH(
        route = TesterRoutes.ROUTE_SEARCH,
        label = "Search",
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search
    ),
    HANDOFF(
        route = TesterRoutes.ROUTE_HANDOFF,
        label = "Handoff",
        selectedIcon = Icons.Filled.SwapHoriz,
        unselectedIcon = Icons.Outlined.SwapHoriz
    ),
    WORKER(
        route = TesterRoutes.ROUTE_WORKER,
        label = "Worker",
        selectedIcon = Icons.Filled.Dns,
        unselectedIcon = Icons.Outlined.Dns
    ),
    DOWNLOAD(
        route = TesterRoutes.ROUTE_DOWNLOAD,
        label = "Download",
        selectedIcon = Icons.Filled.CloudDownload,
        unselectedIcon = Icons.Outlined.CloudDownload
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesterAppRoot(
    container: TesterAppContainer,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: TesterRoutes.ROUTE_DASHBOARD

    val dashboardViewModel: DashboardViewModel = viewModel(factory = container.viewModelFactory)
    val searchViewModel: SearchViewModel = viewModel(factory = container.viewModelFactory)
    val handoffViewModel: HandoffViewModel = viewModel(factory = container.viewModelFactory)
    val workerMonitorViewModel: WorkerMonitorViewModel = viewModel(factory = container.viewModelFactory)
    val directDownloadViewModel: DirectDownloadViewModel = viewModel(factory = container.viewModelFactory)

    val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
    var showConfigSheet by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                text = "FLACDL Direct Tester",
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        actions = {
                            ModeChipButton(
                                mode = dashboardState.config.mode,
                                onClick = { showConfigSheet = true },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    HorizontalDivider(color = HairlineBorder)
                }
            },
            bottomBar = {
                if (!useNavigationRail) {
                    Column {
                        HorizontalDivider(color = HairlineBorder)
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            TesterDestination.entries.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateSingleTop(navController, dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = dest.label,
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        TesterDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateSingleTop(navController, dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = TesterRoutes.ROUTE_DASHBOARD,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(TesterRoutes.ROUTE_DASHBOARD) {
                        DashboardScreen(
                            uiState = dashboardState,
                            onTestApiConnection = { dashboardViewModel.testApiConnection() },
                            onStartNewTest = {
                                dashboardViewModel.startNewTest()
                                workerMonitorViewModel.resetHistory()
                                directDownloadViewModel.resetDownloadState()
                                searchViewModel.prepareNewTestFlow()
                                navigateSingleTop(navController, TesterRoutes.ROUTE_SEARCH)
                            },
                            onOpenConfigSheet = { showConfigSheet = true }
                        )
                    }

                    composable(TesterRoutes.ROUTE_SEARCH) {
                        val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
                        val mode by searchViewModel.currentMode.collectAsStateWithLifecycle()
                        SearchScreen(
                            uiState = searchState,
                            activeMode = mode,
                            onQueryChanged = { searchViewModel.onQueryChanged(it) },
                            onSearchSubmit = { searchViewModel.search() },
                            onSelectTrack = { track ->
                                searchViewModel.selectTrack(track)
                                workerMonitorViewModel.resetHistory()
                                directDownloadViewModel.resetDownloadState()
                                navigateSingleTop(navController, TesterRoutes.ROUTE_HANDOFF)
                            }
                        )
                    }

                    composable(TesterRoutes.ROUTE_HANDOFF) {
                        val handoffState by handoffViewModel.uiState.collectAsStateWithLifecycle()
                        HandoffScreen(
                            uiState = handoffState,
                            onCreateHandoff = { handoffViewModel.createHandoff() },
                            onGoToSearch = { navigateSingleTop(navController, TesterRoutes.ROUTE_SEARCH) },
                            onGoToWorkerMonitor = {
                                navigateSingleTop(navController, TesterRoutes.ROUTE_WORKER)
                            }
                        )
                    }

                    composable(TesterRoutes.ROUTE_WORKER) {
                        val workerState by workerMonitorViewModel.uiState.collectAsStateWithLifecycle()
                        WorkerMonitorScreen(
                            uiState = workerState,
                            onStartPolling = { workerMonitorViewModel.startPolling() },
                            onStopPolling = { workerMonitorViewModel.stopPolling() },
                            onPollOnce = { workerMonitorViewModel.pollOnce() },
                            onGoToHandoff = { navigateSingleTop(navController, TesterRoutes.ROUTE_HANDOFF) },
                            onGoToDirectDownload = {
                                workerMonitorViewModel.stopPolling()
                                navigateSingleTop(navController, TesterRoutes.ROUTE_DOWNLOAD)
                            }
                        )
                    }

                    composable(TesterRoutes.ROUTE_DOWNLOAD) {
                        val downloadState by directDownloadViewModel.uiState.collectAsStateWithLifecycle()
                        DirectDownloadScreen(
                            uiState = downloadState,
                            onStartDownload = { directDownloadViewModel.startDirectDownload() },
                            onCancelDownload = { directDownloadViewModel.cancelDownload() },
                            onExportSafUri = { uri -> directDownloadViewModel.exportWithSaf(uri) },
                            onSendDiagnosticReport = { directDownloadViewModel.sendDiagnosticReport() },
                            onGoToWorkerMonitor = {
                                navigateSingleTop(navController, TesterRoutes.ROUTE_WORKER)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showConfigSheet) {
        ModeConfigBottomSheet(
            config = dashboardState.config,
            onDismiss = { showConfigSheet = false },
            onSaveConfig = { mode, baseUrl, healthPath, searchPath, handoffPath, reportPath, reportVerified, bearerToken ->
                dashboardViewModel.saveSessionConfig(
                    mode = mode,
                    baseUrl = baseUrl,
                    healthPath = healthPath,
                    searchPath = searchPath,
                    handoffPath = handoffPath,
                    reportPath = reportPath,
                    reportVerified = reportVerified,
                    bearerToken = bearerToken
                )
            }
        )
    }
}

private fun navigateSingleTop(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
