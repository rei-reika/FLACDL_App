package com.example.data.remote

import com.example.core.config.TesterSessionConfig
import com.example.data.model.ApiCallResult
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.HandoffResponse
import com.example.data.model.HealthCheckInfo
import com.example.data.model.SearchResponse
import com.example.data.model.SearchTrackItem
import com.example.data.model.WorkerPollResponse

/**
 * Contract for communicating with the FLACDL POC Control Plane (VPS) and Data Plane (Lucida Worker).
 */
interface FlacdlApiClient {
    suspend fun checkVpsHealth(config: TesterSessionConfig): ApiCallResult<HealthCheckInfo>
    suspend fun searchTracks(config: TesterSessionConfig, query: String): ApiCallResult<List<SearchTrackItem>>
    suspend fun searchCatalog(config: TesterSessionConfig, query: String): ApiCallResult<SearchResponse>
    suspend fun createHandoff(config: TesterSessionConfig, trackUrl: String): ApiCallResult<HandoffResponse>
    suspend fun pollWorker(
        config: TesterSessionConfig,
        pollUrl: String,
        serverHint: String
    ): ApiCallResult<WorkerPollResponse>
    suspend fun sendDiagnosticReport(
        config: TesterSessionConfig,
        report: DiagnosticReportRequest
    ): ApiCallResult<String>
}

typealias ApiClient = FlacdlApiClient
