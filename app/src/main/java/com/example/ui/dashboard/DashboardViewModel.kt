package com.example.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.data.model.DashboardMetrics
import com.example.data.repository.TesterRepository
import com.example.domain.TestApiConnectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val config: TesterSessionConfig = TesterSessionConfig(),
    val metrics: DashboardMetrics = DashboardMetrics(),
    val isTestingConnection: Boolean = false
)

class DashboardViewModel(
    private val repository: TesterRepository,
    private val testApiConnectionUseCase: TestApiConnectionUseCase
) : ViewModel() {

    private val isTesting = MutableStateFlow(false)

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.configRepository.config,
        repository.dashboardMetrics,
        isTesting
    ) { config, metrics, testing ->
        DashboardUiState(
            config = config,
            metrics = metrics,
            isTestingConnection = testing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )

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
