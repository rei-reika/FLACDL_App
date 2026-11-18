package com.example.domain

import com.example.data.model.ApiCallResult
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.HandoffResponse
import com.example.data.model.HealthCheckInfo
import com.example.data.model.SearchResponse
import com.example.data.model.SearchTrackItem
import com.example.data.model.WorkerPollResponse
import com.example.data.repository.TesterRepository

class TestApiConnectionUseCase(
    private val repository: TesterRepository
) {
    suspend operator fun invoke(): ApiCallResult<HealthCheckInfo> = repository.testApiConnection()
}

class SearchTracksUseCase(
    private val repository: TesterRepository
) {
    suspend operator fun invoke(query: String): ApiCallResult<List<SearchTrackItem>> =
        repository.searchTracks(query)

    suspend fun searchCatalog(query: String): ApiCallResult<SearchResponse> =
        repository.searchCatalog(query)
}

class CreateHandoffUseCase(
    private val repository: TesterRepository
) {
    suspend operator fun invoke(trackUrl: String): ApiCallResult<HandoffResponse> =
        repository.createHandoff(trackUrl)
}

class PollWorkerStatusUseCase(
    private val repository: TesterRepository
) {
    suspend operator fun invoke(): ApiCallResult<WorkerPollResponse> =
        repository.pollWorkerStatus()
}

class SendDiagnosticReportUseCase(
    private val repository: TesterRepository
) {
    suspend operator fun invoke(report: DiagnosticReportRequest): ApiCallResult<String> =
        repository.sendDiagnosticReport(report)
}
