package com.example.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.core.config.SessionConfigRepository
import com.example.data.repository.TesterRepository
import com.example.domain.CreateHandoffUseCase
import com.example.domain.PollWorkerStatusUseCase
import com.example.domain.SearchTracksUseCase
import com.example.domain.SendDiagnosticReportUseCase
import com.example.domain.TestApiConnectionUseCase
import com.example.download.DirectDownloadManager
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.download.DirectDownloadViewModel
import com.example.ui.handoff.HandoffViewModel
import com.example.ui.search.SearchViewModel
import com.example.ui.worker.WorkerMonitorViewModel

class TesterAppContainer(appContext: Context) {

    val sessionConfigRepository = SessionConfigRepository()
    val testerRepository = TesterRepository(configRepository = sessionConfigRepository)
    val directDownloadManager = DirectDownloadManager(context = appContext.applicationContext)

    private val testApiConnectionUseCase = TestApiConnectionUseCase(testerRepository)
    private val searchTracksUseCase = SearchTracksUseCase(testerRepository)
    private val createHandoffUseCase = CreateHandoffUseCase(testerRepository)
    private val pollWorkerStatusUseCase = PollWorkerStatusUseCase(testerRepository)
    private val sendDiagnosticReportUseCase = SendDiagnosticReportUseCase(testerRepository)

    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when {
                modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
                    DashboardViewModel(testerRepository, testApiConnectionUseCase) as T
                modelClass.isAssignableFrom(SearchViewModel::class.java) ->
                    SearchViewModel(testerRepository, searchTracksUseCase) as T
                modelClass.isAssignableFrom(HandoffViewModel::class.java) ->
                    HandoffViewModel(testerRepository, createHandoffUseCase) as T
                modelClass.isAssignableFrom(WorkerMonitorViewModel::class.java) ->
                    WorkerMonitorViewModel(testerRepository, pollWorkerStatusUseCase) as T
                modelClass.isAssignableFrom(DirectDownloadViewModel::class.java) ->
                    DirectDownloadViewModel(
                        testerRepository,
                        directDownloadManager,
                        sendDiagnosticReportUseCase
                    ) as T
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
