package com.example.ui.settings

import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuthType

sealed class ServerConnectionStatusUi {
    data object Idle : ServerConnectionStatusUi()
    data object Testing : ServerConnectionStatusUi()
    data class Connected(val summary: String) : ServerConnectionStatusUi()
    data class Failed(val category: String, val message: String) : ServerConnectionStatusUi()
}

/**
 * UI-safe projection of an OpenSubsonic server profile.
 * Intentionally excludes raw passwords, tokens, salts, and API keys.
 */
data class OpenSubsonicServerCardUi(
    val id: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val authType: OpenSubsonicAuthType,
    val enabled: Boolean,
    val connectionStatus: ServerConnectionStatusUi = ServerConnectionStatusUi.Idle
)

data class OpenSubsonicServerFormState(
    val isOpen: Boolean = false,
    val editingServerId: String? = null,
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val authType: OpenSubsonicAuthType = OpenSubsonicAuthType.TOKEN,
    val credentialInput: String = "",
    val hasExistingCredential: Boolean = false,
    val enabled: Boolean = true,
    val validationErrors: List<String> = emptyList(),
    val isTestingConnection: Boolean = false,
    val testConnectionStatus: ServerConnectionStatusUi = ServerConnectionStatusUi.Idle
) {
    val isEditMode: Boolean
        get() = editingServerId != null
}

data class OpenSubsonicSettingsUiState(
    val servers: List<OpenSubsonicServerCardUi> = emptyList(),
    val form: OpenSubsonicServerFormState = OpenSubsonicServerFormState(),
    val pendingDeleteServer: OpenSubsonicServerCardUi? = null,
    val statusNotice: String? = null
)
