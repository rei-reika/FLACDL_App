package com.example.ui.handoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.config.TesterSessionConfig
import com.example.data.model.HandoffResponse
import com.example.data.model.SearchTrackItem
import com.example.data.repository.TesterRepository
import com.example.domain.CreateHandoffUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HandoffUiState(
    val config: TesterSessionConfig = TesterSessionConfig(),
    val selectedTrack: SearchTrackItem? = null,
    val activeHandoff: HandoffResponse? = null,
    val isCreating: Boolean = false,
    val httpStatusCode: Int? = null,
    val latencyMs: Long? = null,
    val errorMessage: String? = null
)

class HandoffViewModel(
    private val repository: TesterRepository,
    private val createHandoffUseCase: CreateHandoffUseCase
) : ViewModel() {

    private val localMeta = MutableStateFlow(LocalHandoffMeta())

    val uiState: StateFlow<HandoffUiState> = combine(
        repository.configRepository.config,
        repository.selectedTrack,
        repository.activeHandoff,
        localMeta
    ) { config, track, handoff, meta ->
        HandoffUiState(
            config = config,
            selectedTrack = track,
            activeHandoff = handoff,
            isCreating = meta.isCreating,
            httpStatusCode = meta.httpStatusCode,
            latencyMs = meta.latencyMs,
            errorMessage = meta.errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HandoffUiState()
    )

    fun createHandoff() {
        val track = repository.selectedTrack.value ?: return
        if (localMeta.value.isCreating) return

        viewModelScope.launch {
            localMeta.update {
                it.copy(isCreating = true, errorMessage = null)
            }
            val result = createHandoffUseCase(track.trackUrl)
            localMeta.update {
                it.copy(
                    isCreating = false,
                    httpStatusCode = result.httpStatusCode,
                    latencyMs = result.latencyMs,
                    errorMessage = if (result.isSuccess) null else (result.errorMessage ?: "Gagal membuat handoff.")
                )
            }
        }
    }

    private data class LocalHandoffMeta(
        val isCreating: Boolean = false,
        val httpStatusCode: Int? = null,
        val latencyMs: Long? = null,
        val errorMessage: String? = null
    )
}
