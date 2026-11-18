package com.example.ui.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.security.LogSanitizer
import com.example.data.model.HandoffResponse
import com.example.data.model.StatusHistoryEntry
import com.example.data.model.WorkerJobStatus
import com.example.data.repository.TesterRepository
import com.example.domain.PollWorkerStatusUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkerMonitorUiState(
    val activeHandoff: HandoffResponse? = null,
    val workerServer: String = "-",
    val jobStatus: WorkerJobStatus = WorkerJobStatus.UNKNOWN,
    val isPolling: Boolean = false,
    val lastPollTimeFormatted: String = "-",
    val elapsedProcessMs: Long = 0L,
    val lastHttpStatus: Int? = null,
    val lastMessage: String? = null,
    val errorMessage: String? = null,
    val history: List<StatusHistoryEntry> = emptyList()
)

class WorkerMonitorViewModel(
    private val repository: TesterRepository,
    private val pollWorkerStatusUseCase: PollWorkerStatusUseCase
) : ViewModel() {

    private var pollingJob: Job? = null
    private var monitorStartMs: Long = 0L
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val monitorMeta = MutableStateFlow(MonitorMeta())

    val uiState: StateFlow<WorkerMonitorUiState> = combine(
        repository.activeHandoff,
        monitorMeta
    ) { handoff, meta ->
        WorkerMonitorUiState(
            activeHandoff = handoff,
            workerServer = meta.workerServer.ifBlank { handoff?.server ?: "-" },
            jobStatus = meta.jobStatus,
            isPolling = meta.isPolling,
            lastPollTimeFormatted = meta.lastPollTimeFormatted,
            elapsedProcessMs = meta.elapsedProcessMs,
            lastHttpStatus = meta.lastHttpStatus,
            lastMessage = meta.lastMessage,
            errorMessage = meta.errorMessage,
            history = meta.history
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WorkerMonitorUiState()
    )

    fun startPolling() {
        val handoff = repository.activeHandoff.value ?: return
        if (pollingJob?.isActive == true) return

        monitorStartMs = System.currentTimeMillis()
        monitorMeta.update {
            it.copy(
                workerServer = handoff.server,
                isPolling = true,
                errorMessage = null
            )
        }

        pollingJob = viewModelScope.launch {
            while (isActive) {
                val result = pollWorkerStatusUseCase()
                val now = System.currentTimeMillis()
                val timestamp = timeFormatter.format(Date(now))
                val elapsed = now - monitorStartMs

                if (result.isSuccess && result.data != null) {
                    val poll = result.data
                    val note = poll.message ?: "Polling ${LogSanitizer.maskUrlForDisplay(handoff.pollUrl)}"
                    monitorMeta.update { current ->
                        val shouldRecordHistory = current.history.isEmpty() ||
                            current.history.first().status != poll.status ||
                            current.history.first().rawStatus != poll.rawStatus
                        val updatedHistory = if (shouldRecordHistory) {
                            listOf(
                                StatusHistoryEntry(
                                    timestampFormatted = timestamp,
                                    status = poll.status,
                                    rawStatus = poll.rawStatus,
                                    httpStatus = result.httpStatusCode,
                                    latencyMs = result.latencyMs,
                                    note = note
                                )
                            ) + current.history
                        } else {
                            current.history
                        }

                        current.copy(
                            workerServer = poll.server.ifBlank { handoff.server },
                            jobStatus = poll.status,
                            lastPollTimeFormatted = timestamp,
                            elapsedProcessMs = elapsed,
                            lastHttpStatus = result.httpStatusCode,
                            lastMessage = note,
                            errorMessage = null,
                            history = updatedHistory
                        )
                    }

                    if (poll.status == WorkerJobStatus.COMPLETED || poll.status == WorkerJobStatus.FAILED) {
                        stopPolling()
                        break
                    }
                } else {
                    val errMsg = result.errorMessage ?: "Polling worker gagal."
                    monitorMeta.update { current ->
                        current.copy(
                            lastPollTimeFormatted = timestamp,
                            elapsedProcessMs = elapsed,
                            lastHttpStatus = result.httpStatusCode,
                            errorMessage = errMsg,
                            history = listOf(
                                StatusHistoryEntry(
                                    timestampFormatted = timestamp,
                                    status = WorkerJobStatus.UNKNOWN,
                                    rawStatus = "error",
                                    httpStatus = result.httpStatusCode,
                                    latencyMs = result.latencyMs,
                                    note = errMsg
                                )
                            ) + current.history
                        )
                    }
                    stopPolling()
                    break
                }

                delay(1_200)
            }
        }
    }

    fun pollOnce() {
        val handoff = repository.activeHandoff.value ?: return
        if (pollingJob?.isActive == true) return
        if (monitorStartMs == 0L) monitorStartMs = System.currentTimeMillis()

        viewModelScope.launch {
            val result = pollWorkerStatusUseCase()
            val now = System.currentTimeMillis()
            val timestamp = timeFormatter.format(Date(now))
            val elapsed = now - monitorStartMs
            if (result.isSuccess && result.data != null) {
                val poll = result.data
                val note = poll.message ?: "Polling tunggal (${LogSanitizer.maskUrlForDisplay(handoff.pollUrl)})"
                monitorMeta.update { current ->
                    current.copy(
                        workerServer = poll.server.ifBlank { handoff.server },
                        jobStatus = poll.status,
                        lastPollTimeFormatted = timestamp,
                        elapsedProcessMs = elapsed,
                        lastHttpStatus = result.httpStatusCode,
                        lastMessage = note,
                        errorMessage = null,
                        history = listOf(
                            StatusHistoryEntry(
                                timestampFormatted = timestamp,
                                status = poll.status,
                                rawStatus = poll.rawStatus,
                                httpStatus = result.httpStatusCode,
                                latencyMs = result.latencyMs,
                                note = note
                            )
                        ) + current.history
                    )
                }
            } else {
                monitorMeta.update {
                    it.copy(
                        lastPollTimeFormatted = timestamp,
                        elapsedProcessMs = elapsed,
                        lastHttpStatus = result.httpStatusCode,
                        errorMessage = result.errorMessage ?: "Polling tunggal gagal."
                    )
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        monitorMeta.update { it.copy(isPolling = false) }
    }

    fun resetHistory() {
        stopPolling()
        monitorStartMs = 0L
        monitorMeta.value = MonitorMeta()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    private data class MonitorMeta(
        val workerServer: String = "",
        val jobStatus: WorkerJobStatus = WorkerJobStatus.UNKNOWN,
        val isPolling: Boolean = false,
        val lastPollTimeFormatted: String = "-",
        val elapsedProcessMs: Long = 0L,
        val lastHttpStatus: Int? = null,
        val lastMessage: String? = null,
        val errorMessage: String? = null,
        val history: List<StatusHistoryEntry> = emptyList()
    )
}
