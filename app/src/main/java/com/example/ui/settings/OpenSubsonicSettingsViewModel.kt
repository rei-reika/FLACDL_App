package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuthType
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigRepository
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigValidationResult
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConnectionResult
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConnectionValidator
import com.example.catalog.resolver.opensubsonic.OpenSubsonicServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class OpenSubsonicSettingsViewModel(
    private val configRepository: OpenSubsonicConfigRepository,
    private val connectionValidator: OpenSubsonicConnectionValidator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val connectionStatuses = MutableStateFlow<Map<String, ServerConnectionStatusUi>>(emptyMap())

    private val _uiState = MutableStateFlow(
        OpenSubsonicSettingsUiState(
            servers = mapServers(configRepository.getAllServers(), emptyMap())
        )
    )
    val uiState: StateFlow<OpenSubsonicSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            configRepository.servers.collect { profiles ->
                val statuses = connectionStatuses.value
                _uiState.update { current ->
                    current.copy(servers = mapServers(profiles, statuses))
                }
            }
        }
    }

    fun openAddServerForm() {
        _uiState.update {
            it.copy(
                statusNotice = null,
                form = OpenSubsonicServerFormState(isOpen = true)
            )
        }
    }

    fun openEditServerForm(serverId: String) {
        val profile = configRepository.getServer(serverId) ?: return
        _uiState.update {
            it.copy(
                statusNotice = null,
                form = OpenSubsonicServerFormState(
                    isOpen = true,
                    editingServerId = profile.id,
                    name = profile.name,
                    serverUrl = profile.serverUrl,
                    username = profile.username,
                    authType = profile.authType,
                    // Never expose stored credentials in the form
                    credentialInput = "",
                    hasExistingCredential = true,
                    enabled = profile.enabled
                )
            )
        }
    }

    fun dismissForm() {
        _uiState.update { it.copy(form = OpenSubsonicServerFormState(isOpen = false)) }
    }

    fun onFormNameChanged(value: String) {
        _uiState.update {
            it.copy(form = it.form.copy(name = value, validationErrors = emptyList()))
        }
    }

    fun onFormUrlChanged(value: String) {
        _uiState.update {
            it.copy(form = it.form.copy(serverUrl = value, validationErrors = emptyList(), testConnectionStatus = ServerConnectionStatusUi.Idle))
        }
    }

    fun onFormUsernameChanged(value: String) {
        _uiState.update {
            it.copy(form = it.form.copy(username = value, validationErrors = emptyList(), testConnectionStatus = ServerConnectionStatusUi.Idle))
        }
    }

    fun onFormAuthTypeChanged(authType: OpenSubsonicAuthType) {
        _uiState.update {
            it.copy(form = it.form.copy(authType = authType, validationErrors = emptyList(), testConnectionStatus = ServerConnectionStatusUi.Idle))
        }
    }

    fun onFormCredentialChanged(value: String) {
        _uiState.update {
            it.copy(form = it.form.copy(credentialInput = value, validationErrors = emptyList(), testConnectionStatus = ServerConnectionStatusUi.Idle))
        }
    }

    fun onFormEnabledChanged(enabled: Boolean) {
        _uiState.update {
            it.copy(form = it.form.copy(enabled = enabled))
        }
    }

    fun saveForm() {
        val form = _uiState.value.form
        val profile = buildProfileFromForm(form)
        if (profile == null) {
            _uiState.update {
                it.copy(form = it.form.copy(validationErrors = listOf("Server configuration no longer exists.")))
            }
            return
        }

        val result = if (form.isEditMode) {
            configRepository.updateServer(profile)
        } else {
            configRepository.saveServer(profile)
        }

        when (result) {
            is OpenSubsonicConfigValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(form = it.form.copy(validationErrors = result.errors))
                }
            }

            is OpenSubsonicConfigValidationResult.Valid -> {
                // Reset stale per-card connection status when server config changes
                updateCardConnectionStatus(result.normalizedProfile.id, ServerConnectionStatusUi.Idle)
                _uiState.update {
                    it.copy(
                        form = OpenSubsonicServerFormState(isOpen = false),
                        statusNotice = if (form.isEditMode) {
                            "Server \"${result.normalizedProfile.name}\" diperbarui."
                        } else {
                            "Server \"${result.normalizedProfile.name}\" ditambahkan."
                        }
                    )
                }
            }
        }
    }

    fun toggleServerEnabled(serverId: String, enabled: Boolean) {
        configRepository.setServerEnabled(serverId, enabled)
    }

    fun requestDeleteServer(serverId: String) {
        val card = _uiState.value.servers.firstOrNull { it.id == serverId } ?: return
        _uiState.update { it.copy(pendingDeleteServer = card) }
    }

    fun cancelDeleteServer() {
        _uiState.update { it.copy(pendingDeleteServer = null) }
    }

    fun confirmDeleteServer() {
        val pending = _uiState.value.pendingDeleteServer ?: return
        configRepository.removeServer(pending.id)
        connectionStatuses.update { it - pending.id }
        _uiState.update {
            it.copy(
                pendingDeleteServer = null,
                statusNotice = "Server \"${pending.name}\" dihapus."
            )
        }
    }

    fun testServerConnection(serverId: String) {
        val currentStatus = connectionStatuses.value[serverId]
        if (currentStatus is ServerConnectionStatusUi.Testing) return
        val profile = configRepository.getServer(serverId) ?: return

        updateCardConnectionStatus(serverId, ServerConnectionStatusUi.Testing)
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    connectionValidator.validateConnection(profile)
                }
                updateCardConnectionStatus(serverId, result.toUiStatus())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateCardConnectionStatus(
                    serverId,
                    ServerConnectionStatusUi.Failed("Server Error", "Validasi koneksi gagal.")
                )
            }
        }
    }

    fun testFormConnection() {
        val form = _uiState.value.form
        if (form.isTestingConnection) return
        val profile = buildProfileFromForm(form) ?: return

        _uiState.update {
            it.copy(
                form = it.form.copy(
                    isTestingConnection = true,
                    validationErrors = emptyList(),
                    testConnectionStatus = ServerConnectionStatusUi.Testing
                )
            )
        }

        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    connectionValidator.validateConnection(profile)
                }
                val uiStatus = result.toUiStatus()
                val validationErrors = if (result is OpenSubsonicConnectionResult.InvalidConfig) {
                    result.errors
                } else {
                    emptyList()
                }
                _uiState.update {
                    it.copy(
                        form = it.form.copy(
                            isTestingConnection = false,
                            validationErrors = validationErrors,
                            testConnectionStatus = uiStatus
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        form = it.form.copy(
                            isTestingConnection = false,
                            testConnectionStatus = ServerConnectionStatusUi.Failed("Server Error", "Validasi koneksi gagal.")
                        )
                    )
                }
            }
        }
    }

    private fun buildProfileFromForm(form: OpenSubsonicServerFormState): OpenSubsonicServerProfile? {
        val existing = form.editingServerId?.let { configRepository.getServer(it) }
        if (form.isEditMode && existing == null) return null

        val enteredCredential = form.credentialInput.trim()
        val effectiveCredential = if (enteredCredential.isNotEmpty()) {
            enteredCredential
        } else {
            // Preserve existing saved credential when editing with a blank credential field
            existing?.credential.orEmpty()
        }
        // Only preserve existing salt if the user did NOT replace the credential and authType is unchanged
        val effectiveSalt = if (enteredCredential.isEmpty() && existing?.authType == form.authType) {
            existing.salt
        } else {
            null
        }

        return OpenSubsonicServerProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = form.name,
            serverUrl = form.serverUrl,
            username = form.username,
            credential = effectiveCredential,
            authType = form.authType,
            salt = effectiveSalt,
            enabled = form.enabled,
            timeoutMs = existing?.timeoutMs ?: 10_000L,
            maxCandidates = existing?.maxCandidates ?: 10
        )
    }

    private fun updateCardConnectionStatus(serverId: String, status: ServerConnectionStatusUi) {
        connectionStatuses.update { it + (serverId to status) }
        val statuses = connectionStatuses.value
        _uiState.update { current ->
            current.copy(servers = mapServers(configRepository.getAllServers(), statuses))
        }
    }

    private fun mapServers(
        profiles: List<OpenSubsonicServerProfile>,
        statuses: Map<String, ServerConnectionStatusUi>
    ): List<OpenSubsonicServerCardUi> {
        return profiles.map { p ->
            OpenSubsonicServerCardUi(
                id = p.id,
                name = p.name,
                serverUrl = p.serverUrl,
                username = p.username,
                authType = p.authType,
                enabled = p.enabled,
                connectionStatus = statuses[p.id] ?: ServerConnectionStatusUi.Idle
            )
        }
    }

    private fun OpenSubsonicConnectionResult.toUiStatus(): ServerConnectionStatusUi {
        return when (this) {
            is OpenSubsonicConnectionResult.Connected -> {
                val parts = buildList {
                    serverType?.takeIf { it.isNotBlank() }?.let { add(it) }
                    serverVersion?.takeIf { it.isNotBlank() }?.let { add("v$it") }
                    add("${latencyMs}ms")
                }
                ServerConnectionStatusUi.Connected(parts.joinToString(" • "))
            }

            is OpenSubsonicConnectionResult.InvalidConfig ->
                ServerConnectionStatusUi.Failed("Config Error", errors.firstOrNull() ?: "Konfigurasi tidak valid.")

            is OpenSubsonicConnectionResult.AuthenticationFailed ->
                ServerConnectionStatusUi.Failed("Auth Failed", "Autentikasi ditolak oleh server. Periksa username/kredensial.")

            is OpenSubsonicConnectionResult.Timeout ->
                ServerConnectionStatusUi.Failed("Timeout", "Koneksi ke server melebihi batas waktu.")

            is OpenSubsonicConnectionResult.NetworkError ->
                ServerConnectionStatusUi.Failed("Network Error", "Server tidak dapat dijangkau. Periksa URL dan jaringan.")

            is OpenSubsonicConnectionResult.ServerError ->
                ServerConnectionStatusUi.Failed("Server Error", "Server mengembalikan respons error atau tidak valid.")
        }
    }
}
