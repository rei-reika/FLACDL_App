package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuthType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.StatusPill
import com.example.ui.components.TesterSectionCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanMutedContainer
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusDisconnectedContainer
import com.example.ui.theme.StatusTesting
import com.example.ui.theme.StatusTestingContainer
import com.example.ui.theme.StatusUnknown
import com.example.ui.theme.StatusUnknownContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSubsonicSettingsScreen(
    uiState: OpenSubsonicSettingsUiState,
    onBack: () -> Unit,
    onAddServerClick: () -> Unit,
    onEditServerClick: (String) -> Unit,
    onDeleteServerClick: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onTestServerConnection: (String) -> Unit,
    onDismissForm: () -> Unit,
    onFormNameChanged: (String) -> Unit,
    onFormUrlChanged: (String) -> Unit,
    onFormUsernameChanged: (String) -> Unit,
    onFormAuthTypeChanged: (OpenSubsonicAuthType) -> Unit,
    onFormCredentialChanged: (String) -> Unit,
    onFormEnabledChanged: (Boolean) -> Unit,
    onTestFormConnection: () -> Unit,
    onSaveForm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("opensubsonic_settings_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "settings_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("opensubsonic_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Kembali"
                            )
                        }
                        Column {
                            Text(
                                text = "OpenSubsonic Servers",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Pustaka musik pribadi (Navidrome / OpenSubsonic)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onAddServerClick,
                        border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("opensubsonic_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (uiState.statusNotice != null) {
                item(key = "settings_notice") {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = StatusConnectedContainer,
                        border = BorderStroke(1.dp, StatusConnected.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = StatusConnected,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = uiState.statusNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (uiState.servers.isEmpty()) {
                item(key = "settings_empty") {
                    EmptyStatePanel(
                        title = "Belum ada server OpenSubsonic",
                        subtitle = "Tambahkan server Navidrome atau OpenSubsonic milik Anda untuk memeriksa ketersediaan lagu di katalog lokal. Kredensial disimpan terenkripsi di Android Keystore.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(30.dp)
                            )
                        },
                        modifier = Modifier.testTag("opensubsonic_empty_panel")
                    )
                }
            } else {
                items(
                    items = uiState.servers,
                    key = { "server_${it.id}" }
                ) { server ->
                    OpenSubsonicServerCard(
                        server = server,
                        onToggleEnabled = { enabled -> onToggleEnabled(server.id, enabled) },
                        onTestConnection = { onTestServerConnection(server.id) },
                        onEdit = { onEditServerClick(server.id) },
                        onDelete = { onDeleteServerClick(server.id) }
                    )
                }
            }
        }
    }

    if (uiState.form.isOpen) {
        OpenSubsonicServerFormSheet(
            form = uiState.form,
            onDismiss = onDismissForm,
            onNameChanged = onFormNameChanged,
            onUrlChanged = onFormUrlChanged,
            onUsernameChanged = onFormUsernameChanged,
            onAuthTypeChanged = onFormAuthTypeChanged,
            onCredentialChanged = onFormCredentialChanged,
            onEnabledChanged = onFormEnabledChanged,
            onTestConnection = onTestFormConnection,
            onSave = onSaveForm
        )
    }

    val pendingDelete = uiState.pendingDeleteServer
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Hapus Server?") },
            text = {
                Text(
                    "Konfigurasi dan kredensial terenkripsi untuk \"${pendingDelete.name}\" akan dihapus dari perangkat ini."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmDelete,
                    modifier = Modifier.testTag("opensubsonic_confirm_delete_button")
                ) {
                    Text("Hapus", color = StatusDisconnected)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun OpenSubsonicServerCard(
    server: OpenSubsonicServerCardUi,
    onToggleEnabled: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    TesterSectionCard(
        modifier = Modifier.testTag("opensubsonic_server_card_${server.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusPill(
                        text = if (server.enabled) "Enabled" else "Disabled",
                        dotColor = if (server.enabled) StatusConnected else StatusUnknown,
                        containerColor = if (server.enabled) StatusConnectedContainer else StatusUnknownContainer
                    )
                }
                Text(
                    text = server.serverUrl,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val authSummary = if (server.authType == OpenSubsonicAuthType.API_KEY) {
                    "Auth: API Key"
                } else {
                    "Auth: Token • ${server.username}"
                }
                Text(
                    text = authSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("opensubsonic_toggle_${server.id}")
            )
        }

        ConnectionStatusRow(status = server.connectionStatus)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isTesting = server.connectionStatus is ServerConnectionStatusUi.Testing
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !isTesting,
                border = BorderStroke(1.dp, HairlineBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("opensubsonic_test_${server.id}")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = CyanAccent
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.NetworkCheck,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isTesting) "Testing..." else "Test",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("opensubsonic_edit_${server.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit server",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("opensubsonic_delete_${server.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Hapus server",
                    tint = StatusDisconnected,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusRow(status: ServerConnectionStatusUi) {
    when (status) {
        ServerConnectionStatusUi.Idle -> Unit
        ServerConnectionStatusUi.Testing -> {
            StatusPill(
                text = "Menguji koneksi...",
                dotColor = StatusTesting,
                containerColor = StatusTestingContainer
            )
        }

        is ServerConnectionStatusUi.Connected -> {
            StatusPill(
                text = "Connected • ${status.summary}",
                dotColor = StatusConnected,
                containerColor = StatusConnectedContainer
            )
        }

        is ServerConnectionStatusUi.Failed -> {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = StatusDisconnectedContainer,
                border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = StatusDisconnected,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${status.category}: ${status.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSubsonicServerFormSheet(
    form: OpenSubsonicServerFormState,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onAuthTypeChanged: (OpenSubsonicAuthType) -> Unit,
    onCredentialChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCredential by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("opensubsonic_form_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (form.isEditMode) "Edit Server OpenSubsonic" else "Tambah Server OpenSubsonic",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = form.name,
                onValueChange = onNameChanged,
                label = { Text("Nama Server") },
                placeholder = { Text("Contoh: Home Navidrome") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opensubsonic_form_name")
            )

            OutlinedTextField(
                value = form.serverUrl,
                onValueChange = onUrlChanged,
                label = { Text("Server URL") },
                placeholder = { Text("https://music.example.com") },
                supportingText = {
                    Text("Wajib HTTPS untuk host publik. HTTP hanya diizinkan untuk jaringan lokal/LAN.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opensubsonic_form_url")
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Autentikasi:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OpenSubsonicAuthType.entries.forEach { authType ->
                    FilterChip(
                        selected = form.authType == authType,
                        onClick = { onAuthTypeChanged(authType) },
                        label = {
                            Text(if (authType == OpenSubsonicAuthType.TOKEN) "Token / Password" else "API Key")
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanMutedContainer,
                            selectedLabelColor = CyanAccent
                        )
                    )
                }
            }

            if (form.authType == OpenSubsonicAuthType.TOKEN) {
                OutlinedTextField(
                    value = form.username,
                    onValueChange = onUsernameChanged,
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("opensubsonic_form_username")
                )
            }

            OutlinedTextField(
                value = form.credentialInput,
                onValueChange = onCredentialChanged,
                label = {
                    Text(if (form.authType == OpenSubsonicAuthType.API_KEY) "API Key" else "Password / Token")
                },
                placeholder = {
                    if (form.isEditMode && form.hasExistingCredential) {
                        Text("Kosongkan untuk mempertahankan kredensial tersimpan")
                    }
                },
                supportingText = {
                    if (form.isEditMode && form.hasExistingCredential) {
                        Text("Kredensial tersimpan tidak ditampilkan. Isi hanya jika ingin mengganti.")
                    } else {
                        Text("Disimpan terenkripsi menggunakan Android Keystore.")
                    }
                },
                singleLine = true,
                visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showCredential = !showCredential }) {
                        Icon(
                            imageVector = if (showCredential) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showCredential) "Sembunyikan" else "Tampilkan"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opensubsonic_form_credential")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Aktifkan server untuk resolusi sumber",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = form.enabled,
                    onCheckedChange = onEnabledChanged
                )
            }

            if (form.validationErrors.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = StatusDisconnectedContainer,
                    border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        form.validationErrors.forEach { err ->
                            Text(
                                text = "• $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            ConnectionStatusRow(status = form.testConnectionStatus)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !form.isTestingConnection,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("opensubsonic_form_test_button")
                ) {
                    if (form.isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = CyanAccent
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (form.isTestingConnection) "Testing..." else "Test Connection")
                }

                Button(
                    onClick = onSave,
                    enabled = !form.isTestingConnection,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("opensubsonic_form_save_button")
                ) {
                    Text(if (form.isEditMode) "Simpan" else "Tambah")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
