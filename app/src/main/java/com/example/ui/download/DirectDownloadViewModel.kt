package com.example.ui.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.config.TesterSessionConfig
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.DownloadProgressState
import com.example.data.model.HandoffResponse
import com.example.data.repository.TesterRepository
import com.example.domain.SendDiagnosticReportUseCase
import com.example.download.DirectDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DirectDownloadUiState(
    val config: TesterSessionConfig = TesterSessionConfig(),
    val activeHandoff: HandoffResponse? = null,
    val downloadUrlReady: Boolean = false,
    val progress: DownloadProgressState = DownloadProgressState(),
    val isSendingReport: Boolean = false,
    val reportStatusMessage: String? = null
)

class DirectDownloadViewModel(
    private val repository: TesterRepository,
    private val downloadManager: DirectDownloadManager,
    private val sendDiagnosticReportUseCase: SendDiagnosticReportUseCase
) : ViewModel() {

    private val reportState = MutableStateFlow(ReportStatus())

    val uiState: StateFlow<DirectDownloadUiState> = combine(
        repository.configRepository.config,
        repository.activeHandoff,
        downloadManager.state,
        reportState
    ) { config, handoff, progress, report ->
        val hasUrl = !repository.resolveDirectDownloadUrl().isNullOrBlank()
        DirectDownloadUiState(
            config = config,
            activeHandoff = handoff,
            downloadUrlReady = hasUrl,
            progress = progress,
            isSendingReport = report.isSending,
            reportStatusMessage = report.message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DirectDownloadUiState()
    )

    fun startDirectDownload() {
        val handoff = repository.activeHandoff.value ?: return
        val downloadUrl = repository.resolveDirectDownloadUrl() ?: return
        val mode = repository.configRepository.config.value.mode
        val suggestedName = repository.latestWorkerPoll.value?.fileName
            ?: "${handoff.artist} - ${handoff.title}.flac"

        reportState.value = ReportStatus()

        downloadManager.startDirectDownload(
            mode = mode,
            downloadUrl = downloadUrl,
            trackTitle = handoff.title,
            trackArtist = handoff.artist,
            workerServer = handoff.server,
            suggestedFileName = suggestedName,
            onFinished = { success ->
                repository.recordDownloadOutcome(success)
            }
        )
    }

    fun cancelDownload() {
        downloadManager.cancelDownload()
    }

    fun resetDownloadState() {
        reportState.value = ReportStatus()
        downloadManager.resetState()
    }

    fun exportWithSaf(uri: Uri) {
        val result = downloadManager.exportToSafUri(uri)
        result.onFailure { err ->
            reportState.value = ReportStatus(
                message = err.localizedMessage ?: "Gagal menyimpan file lewat SAF."
            )
        }
    }

    fun sendDiagnosticReport() {
        val handoff = repository.activeHandoff.value ?: return
        val currentProgress = downloadManager.state.value
        val validation = currentProgress.validationResult ?: return

        viewModelScope.launch {
            reportState.value = ReportStatus(isSending = true)
            val request = DiagnosticReportRequest(
                testId = handoff.testId,
                handoffId = handoff.handoffId,
                server = handoff.server,
                downloadSuccess = validation.isOverallValid,
                httpStatus = currentProgress.httpStatusCode,
                fileSizeBytes = validation.fileSizeBytes,
                flacSignatureValid = validation.hasFlacSignature,
                sha256Fingerprint = validation.sha256Fingerprint,
                durationMs = currentProgress.elapsedMillis
            )
            val result = sendDiagnosticReportUseCase(request)
            reportState.value = ReportStatus(
                isSending = false,
                message = if (result.isSuccess) {
                    result.data ?: "Laporan terkirim."
                } else {
                    result.errorMessage ?: "Gagal mengirim laporan diagnostik."
                }
            )
        }
    }

    private data class ReportStatus(
        val isSending: Boolean = false,
        val message: String? = null
    )
}
