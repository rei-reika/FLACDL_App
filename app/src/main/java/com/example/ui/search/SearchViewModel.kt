package com.example.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.config.TestMode
import com.example.data.model.SearchTrackItem
import com.example.data.repository.TesterRepository
import com.example.domain.SearchTracksUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val lastExecutedQuery: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val resultCount: Int = 0,
    val results: List<SearchTrackItem> = emptyList(),
    val selectedTrackUrl: String? = null,
    val httpStatusCode: Int? = null,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val isSimulated: Boolean = false
)

class SearchViewModel(
    private val repository: TesterRepository,
    private val searchTracksUseCase: SearchTracksUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SearchUiState(
            selectedTrackUrl = repository.selectedTrack.value?.trackUrl
        )
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val currentMode: StateFlow<TestMode> = MutableStateFlow(
        repository.configRepository.config.value.mode
    ).also { flow ->
        viewModelScope.launch {
            repository.configRepository.config.collect { cfg ->
                flow.value = cfg.mode
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery, errorMessage = null) }
    }

    fun search() {
        val currentQuery = _uiState.value.query
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null
                )
            }
            val result = searchTracksUseCase.searchCatalog(currentQuery)
            _uiState.update { state ->
                if (result.isSuccess && result.data != null) {
                    val payload = result.data
                    state.copy(
                        isLoading = false,
                        hasSearched = true,
                        lastExecutedQuery = payload.query,
                        resultCount = payload.count,
                        results = payload.results,
                        httpStatusCode = result.httpStatusCode,
                        latencyMs = result.latencyMs,
                        errorMessage = null,
                        isSimulated = result.isSimulated
                    )
                } else {
                    // Never fabricate fake search results when Live Mode fails
                    state.copy(
                        isLoading = false,
                        hasSearched = true,
                        lastExecutedQuery = currentQuery.trim(),
                        resultCount = 0,
                        results = emptyList(),
                        httpStatusCode = result.httpStatusCode,
                        latencyMs = result.latencyMs,
                        errorMessage = result.errorMessage ?: "Pencarian gagal.",
                        isSimulated = result.isSimulated
                    )
                }
            }
        }
    }

    /**
     * Stores the selected track in application state for the Handoff screen.
     * Does NOT trigger automatic handoff or automatic download.
     */
    fun selectTrack(track: SearchTrackItem) {
        repository.selectTrack(track)
        _uiState.update { it.copy(selectedTrackUrl = track.trackUrl) }
    }

    fun prepareNewTestFlow() {
        _uiState.value = SearchUiState(query = "")
        if (repository.configRepository.config.value.mode == TestMode.DEMO) {
            search()
        }
    }
}
