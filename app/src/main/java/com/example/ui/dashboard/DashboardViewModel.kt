package com.example.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.data.model.AuthSessionState
import com.example.data.model.DashboardMetrics
import com.example.data.model.NetworkDiagnosticsReport
import com.example.data.repository.TesterRepository
import com.example.domain.TestApiConnectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val config: TesterSessionConfig = TesterSessionConfig(),
    val metrics: DashboardMetrics = DashboardMetrics(),
    val isTestingConnection: Boolean = false,
    val networkDiagnostics: NetworkDiagnosticsReport = NetworkDiagnosticsReport(),
    val authSession: AuthSessionState = AuthSessionState()
)

class DashboardViewModel(
    private val repository: TesterRepository,
    private val testApiConnectionUseCase: TestApiConnectionUseCase
) : ViewModel() {

    private val isTesting = MutableStateFlow(false)

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.configRepository.config,
        repository.dashboardMetrics,
        isTesting,
        repository.networkDiagnostics,
        repository.authSession
    ) { config, metrics, testing, diagnostics, auth ->
        DashboardUiState(
            config = config,
            metrics = metrics,
            isTestingConnection = testing,
            networkDiagnostics = diagnostics,
            authSession = auth
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )

    init {
        viewModelScope.launch {
            repository.restoreSavedSession()
        }
    }

    fun pairDevice(baseUrl: String, pairingCode: String, deviceName: String) {
        if (repository.authSession.value.isBusy) return
        viewModelScope.launch {
            repository.pairWithTelegramCode(
                baseUrl = baseUrl,
                pairingCode = pairingCode,
                deviceName = deviceName
            )
        }
    }

    fun refreshAuthSession() {
        if (repository.authSession.value.isBusy) return
        viewModelScope.launch {
            repository.refreshAuthSession()
        }
    }

    fun logoutDevice() {
        if (repository.authSession.value.isBusy) return
        viewModelScope.launch {
            repository.logoutCurrentDevice()
        }
    }

    fun testApiConnection() {
        if (isTesting.value) return
        viewModelScope.launch {
            isTesting.value = true
            try {
                testApiConnectionUseCase()
            } finally {
                isTesting.value = false
            }
        }
    }

    fun runNetworkDiagnostics() {
        if (repository.networkDiagnostics.value.isRunning) return
        viewModelScope.launch {
            repository.runNetworkDiagnostics(requestsPerEndpoint = 10)
        }
    }

    fun startNewTest() {
        repository.resetActiveTestSession()
    }

    fun saveSessionConfig(
        mode: TestMode,
        baseUrl: String,
        healthPath: String,
        searchPath: String,
        handoffPath: String,
        reportPath: String,
        reportVerified: Boolean,
        bearerToken: String
    ) {
        repository.configRepository.apply {
            setMode(mode)
            updateVpsBaseUrl(baseUrl)
            updateHealthEndpointPath(healthPath)
            updateSearchEndpointPath(searchPath)
            updateHandoffEndpointPath(handoffPath)
            updateDiagnosticReportSettings(reportPath, reportVerified)
            updateSessionToken(bearerToken)
        }
    }
}
