package com.example.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.provider.ItunesCatalogAdapter
import com.example.catalog.remote.OkHttpItunesCatalogClient
import com.example.catalog.remote.OkHttpMusicBrainzCatalogClient
import com.example.catalog.repository.LocalCatalogRepository
import com.example.catalog.resolver.DownloadSourceResolver
import com.example.catalog.resolver.opensubsonic.AndroidKeystoreOpenSubsonicConfigStore
import com.example.catalog.resolver.opensubsonic.ConfiguredOpenSubsonicResolver
import com.example.catalog.resolver.opensubsonic.OkHttpOpenSubsonicClient
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigRepository
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConnectionValidator
import com.example.core.config.SessionConfigRepository
import com.example.core.security.AndroidKeystoreTokenStore
import com.example.data.remote.OkHttpFlacdlApiClient
import com.example.data.repository.TesterRepository
import com.example.domain.CreateHandoffUseCase
import com.example.domain.PollWorkerStatusUseCase
import com.example.domain.SearchTracksUseCase
import com.example.domain.SendDiagnosticReportUseCase
import com.example.domain.TestApiConnectionUseCase
import com.example.download.DirectDownloadManager
import com.example.ui.catalog.CatalogViewModel
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.download.DirectDownloadViewModel
import com.example.ui.handoff.HandoffViewModel
import com.example.ui.search.SearchViewModel
import com.example.ui.settings.OpenSubsonicSettingsViewModel
import com.example.ui.worker.WorkerMonitorViewModel

class TesterAppContainer(appContext: Context) {

    val sessionConfigRepository = SessionConfigRepository()
    private val secureTokenStore = AndroidKeystoreTokenStore(appContext.applicationContext)
    val testerRepository = TesterRepository(
        configRepository = sessionConfigRepository,
        credentialStore = secureTokenStore
    )
    val directDownloadManager = DirectDownloadManager(context = appContext.applicationContext)

    private val sharedHttpClient by lazy {
        OkHttpFlacdlApiClient.defaultOkHttpClient()
    }

    // Independent Local Catalog Engine (Phase 2A) — lazy-initialized, shares OkHttp client conventions
    val localCatalogDatabase: LocalCatalogDatabase by lazy {
        LocalCatalogDatabase.create(appContext.applicationContext)
    }

    val localCatalogRepository: LocalCatalogRepository by lazy {
        LocalCatalogRepository(
            database = localCatalogDatabase,
            catalogProvider = ItunesCatalogAdapter(OkHttpItunesCatalogClient(client = sharedHttpClient)),
            musicBrainzProvider = OkHttpMusicBrainzCatalogClient(client = sharedHttpClient)
        )
    }

    // OpenSubsonic Resolver Configuration & Application-Level Resolver Wiring (Phase 2D.9)
    val openSubsonicConfigRepository: OpenSubsonicConfigRepository by lazy {
        OpenSubsonicConfigRepository(
            store = AndroidKeystoreOpenSubsonicConfigStore(appContext.applicationContext)
        )
    }

    val openSubsonicConnectionValidator: OpenSubsonicConnectionValidator by lazy {
        OpenSubsonicConnectionValidator(
            clientFactory = { cfg -> OkHttpOpenSubsonicClient(cfg, sharedHttpClient) }
        )
    }

    val downloadSourceResolver: DownloadSourceResolver by lazy {
        ConfiguredOpenSubsonicResolver(
            configRepository = openSubsonicConfigRepository,
            clientFactory = { cfg -> OkHttpOpenSubsonicClient(cfg, sharedHttpClient) }
        )
    }

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
                modelClass.isAssignableFrom(CatalogViewModel::class.java) ->
                    CatalogViewModel(
                        repository = localCatalogRepository,
                        downloadSourceResolver = downloadSourceResolver
                    ) as T
                modelClass.isAssignableFrom(OpenSubsonicSettingsViewModel::class.java) ->
                    OpenSubsonicSettingsViewModel(
                        configRepository = openSubsonicConfigRepository,
                        connectionValidator = openSubsonicConnectionValidator
                    ) as T
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
