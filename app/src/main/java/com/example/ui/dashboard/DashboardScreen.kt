package com.example.ui.dashboard

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.security.LogSanitizer
import com.example.data.model.AuthSessionStatus
import com.example.data.model.EndpointDiagnosticsSummary
import com.example.data.model.NetworkLatencySample
import com.example.data.repository.TesterRepository
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.KeyValueTelemetryRow
import com.example.ui.components.StatusPill
import com.example.ui.components.TesterSectionCard
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusTesting
import com.example.ui.theme.StatusTestingContainer

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onTestApiConnection: () -> Unit,
    onStartNewTest: () -> Unit,
    onOpenConfigSheet: () -> Unit,
    onRunNetworkDiagnostics: () -> Unit = {},
    onPairDevice: (baseUrl: String, pairingCode: String, deviceName: String) -> Unit = { _, _, _ -> },
    onRefreshAuthSession: () -> Unit = {},
    onLogoutDevice: () -> Unit = {},
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
                .testTag("dashboard_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Diagnostik Jalur Pengujian",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Control Plane (VPS FLACDL) & Data Plane (Lucida Worker)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Device Pairing & Authentication Card (Phase 4C.1)
            item {
                val auth = uiState.authSession
                val effectiveBaseUrl = uiState.config.vpsBaseUrl.trim()
                    .ifBlank { TesterRepository.DEFAULT_PRODUCTION_BASE_URL }
                var pairingCode by remember { mutableStateOf("") }
                var deviceName by remember {
                    mutableStateOf(
                        (Build.MODEL ?: "Android Device").trim().ifBlank { "Android Device" }
                    )
                }

                TesterSectionCard(modifier = Modifier.testTag("device_auth_card")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Pairing & Sesi Perangkat",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = LogSanitizer.maskUrlForDisplay(effectiveBaseUrl),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val (pillDot, pillContainer) = when (auth.status) {
                            AuthSessionStatus.AUTHENTICATED -> StatusConnected to StatusConnectedContainer
                            AuthSessionStatus.RESTORING, AuthSessionStatus.PAIRING, AuthSessionStatus.NETWORK_ERROR ->
                                StatusTesting to StatusTestingContainer
                            AuthSessionStatus.UNAUTHENTICATED, AuthSessionStatus.REVOKED_OR_INVALID ->
                                StatusDisconnected to MaterialTheme.colorScheme.surfaceVariant
                        }
                        StatusPill(
                            text = auth.status.label,
                            dotColor = pillDot,
                            containerColor = pillContainer,
                            modifier = Modifier.testTag("auth_status_pill")
                        )
                    }

                    HorizontalDivider(color = HairlineBorder)

                    Text(
                        text = auth.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!auth.errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = auth.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusDisconnected,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                                    .testTag("auth_error_banner")
                            )
                        }
                    }

                    val profile = auth.profile
                    if (auth.status == AuthSessionStatus.AUTHENTICATED && profile != null) {
                        val userLabel = if (profile.username.isNotBlank()) {
                            "${profile.fullName} (@${profile.username})"
                        } else {
                            "${profile.fullName} (ID ${profile.userId})"
                        }
                        val quotaLabel = if (profile.isUnlimited) {
                            "Unlimited (Terpakai hari ini: ${profile.usedToday})"
                        } else {
                            "Sisa ${profile.remainingQuota ?: 0} / ${profile.dailyLimit ?: 0} (Terpakai: ${profile.usedToday})"
                        }
                        val pkgLabel = if (profile.daysRemaining != null) {
                            "${profile.packageName} (${profile.daysRemaining} hari)"
                        } else {
                            profile.packageName
                        }

                        KeyValueTelemetryRow(label = "Akun", value = userLabel)
                        KeyValueTelemetryRow(label = "Perangkat", value = "${profile.deviceName} (${profile.deviceId})", monospacedValue = true)
                        KeyValueTelemetryRow(label = "Paket", value = pkgLabel, monospacedValue = true)
                        KeyValueTelemetryRow(label = "Kuota Harian", value = quotaLabel, monospacedValue = true)
                        KeyValueTelemetryRow(label = "Reset WIB", value = profile.resetCountdownWib, monospacedValue = true)
                        KeyValueTelemetryRow(label = "Penyimpanan Token", value = "Android Keystore (AES-GCM)", monospacedValue = true)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRefreshAuthSession,
                                enabled = !auth.isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auth_refresh_button")
                            ) {
                                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh")
                            }
                            OutlinedButton(
                                onClick = onLogoutDevice,
                                enabled = !auth.isBusy,
                                border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auth_logout_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Logout,
                                    contentDescription = null,
                                    tint = StatusDisconnected,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Logout", color = StatusDisconnected)
                            }
                        }
                    } else if (auth.status == AuthSessionStatus.NETWORK_ERROR && auth.hasStoredCredentials) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onRefreshAuthSession,
                                enabled = !auth.isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auth_retry_button")
                            ) {
                                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Coba Lagi")
                            }
                            OutlinedButton(
                                onClick = onLogoutDevice,
                                enabled = !auth.isBusy,
                                border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auth_logout_button")
                            ) {
                                Text("Logout", color = StatusDisconnected)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { input ->
                                pairingCode = input.filter { it.isDigit() }.take(6)
                            },
                            label = { Text("Kode Pairing 6 Digit (dari /android)") },
                            placeholder = { Text("123456") },
                            singleLine = true,
                            enabled = !auth.isBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_pairing_code")
                        )

                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(64) },
                            label = { Text("Nama Perangkat") },
                            singleLine = true,
                            enabled = !auth.isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_device_name")
                        )

                        Button(
                            onClick = {
                                val codeToSend = pairingCode
                                pairingCode = ""
                                onPairDevice(effectiveBaseUrl, codeToSend, deviceName)
                            },
                            enabled = !auth.isBusy && pairingCode.length == 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pair_device_button")
                        ) {
                            if (auth.isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(imageVector = Icons.Outlined.Link, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (auth.isBusy) "Menautkan..." else "Pair Device")
                        }
                    }
                }
            }

            // Connection Status Card
            item {
                TesterSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Status Koneksi",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Surface(
                            onClick = onOpenConfigSheet,
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, HairlineBorder)
                        ) {
                            Text(
                                text = "Ubah Konfigurasi",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = HairlineBorder)

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val useCompactBadge = maxWidth < 360.dp

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "VPS Control Plane",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = if (uiState.config.vpsBaseUrl.isBlank()) {
                                            "URL belum dikonfigurasi (${uiState.config.healthEndpointPath})"
                                        } else {
                                            LogSanitizer.maskUrlForDisplay(uiState.config.vpsBaseUrl)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                ConnectionStatusBadge(
                                    status = uiState.metrics.vpsStatus,
                                    compact = useCompactBadge,
                                    modifier = Modifier.testTag("badge_vps_status")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "Lucida Worker (Data Plane)",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Unduhan langsung tanpa perantara VPS",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                ConnectionStatusBadge(
                                    status = uiState.metrics.workerStatus,
                                    compact = useCompactBadge,
                                    modifier = Modifier.testTag("badge_worker_status")
                                )
                            }
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = uiState.metrics.lastStatusNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "HTTP: ${uiState.metrics.lastHttpStatus?.toString() ?: "-"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Latensi: ${uiState.metrics.lastLatencyMs?.let { "$it ms" } ?: "-"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Mode: ${uiState.config.mode.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            // Health Check Details Card (GET /poc/v1/health)
            item {
                val health = uiState.metrics.latestHealthInfo
                TesterSectionCard(modifier = Modifier.testTag("health_check_detail_card")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MonitorHeart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Detail Health Check VPS",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "GET ${uiState.config.healthEndpointPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (health != null) {
                            if (uiState.metrics.isHealthSimulated) {
                                StatusPill(
                                    text = "Simulasi Demo",
                                    dotColor = StatusTesting,
                                    containerColor = StatusTestingContainer,
                                    modifier = Modifier.testTag("health_source_badge")
                                )
                            } else {
                                StatusPill(
                                    text = "Live Server",
                                    dotColor = StatusConnected,
                                    containerColor = StatusConnectedContainer,
                                    modifier = Modifier.testTag("health_source_badge")
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = HairlineBorder)

                    KeyValueTelemetryRow(
                        label = "status",
                        value = health?.status ?: "Belum diuji",
                        monospacedValue = true,
                        modifier = Modifier.testTag("health_row_status")
                    )
                    KeyValueTelemetryRow(
                        label = "clearance_available",
                        value = health?.clearanceAvailable?.toString() ?: "-",
                        monospacedValue = true,
                        modifier = Modifier.testTag("health_row_clearance")
                    )
                    KeyValueTelemetryRow(
                        label = "has_cf_cookie",
                        value = health?.hasCfCookie?.toString() ?: "-",
                        monospacedValue = true,
                        modifier = Modifier.testTag("health_row_cf_cookie")
                    )
                    KeyValueTelemetryRow(
                        label = "lucidadl_version",
                        value = health?.lucidadlVersion ?: "-",
                        monospacedValue = true,
                        modifier = Modifier.testTag("health_row_lucida_version")
                    )
                    KeyValueTelemetryRow(
                        label = "server_time",
                        value = health?.serverTime ?: "-",
                        monospacedValue = true,
                        modifier = Modifier.testTag("health_row_server_time")
                    )
                    KeyValueTelemetryRow(
                        label = "response time",
                        value = health?.responseTimeMs?.let { "$it ms" }
                            ?: uiState.metrics.lastLatencyMs?.let { "$it ms" }
                            ?: "-",
                        monospacedValue = true,
                        modifier = Modifier.testTag("health_row_response_time")
                    )
                }
            }

            // Network Diagnostics Card (GET /poc/v1/health & GET /poc/v1/auth/me)
            item {
                val diag = uiState.networkDiagnostics
                TesterSectionCard(modifier = Modifier.testTag("network_diagnostics_card")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Network Diagnostics",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "GET ${uiState.config.healthEndpointPath} & GET /poc/v1/auth/me (10x)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (diag.endpoints.isNotEmpty() && !diag.isRunning) {
                            if (diag.isSimulated) {
                                StatusPill(
                                    text = "Simulasi Demo",
                                    dotColor = StatusTesting,
                                    containerColor = StatusTestingContainer
                                )
                            } else {
                                StatusPill(
                                    text = "Live Server",
                                    dotColor = StatusConnected,
                                    containerColor = StatusConnectedContainer
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = HairlineBorder)

                    OutlinedButton(
                        onClick = onRunNetworkDiagnostics,
                        enabled = !diag.isRunning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("run_diagnostics_button"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        if (diag.isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = null
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (diag.isRunning) "Running Diagnostics..." else "Run Diagnostics",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (diag.progressNote.isNotBlank()) {
                        Text(
                            text = diag.progressNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (diag.endpoints.isEmpty() && !diag.isRunning) {
                        Text(
                            text = "Tekan Run Diagnostics untuk mengukur DNS, TCP, TLS, dan total latency (10 request berurutan per endpoint).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            diag.endpoints.forEach { summary ->
                                EndpointDiagnosticsBlock(summary = summary)
                            }
                        }
                    }
                }
            }

            // Metrics Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricBox(
                        label = "Jumlah Pengujian",
                        value = uiState.metrics.totalTests.toString(),
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        label = "Download Berhasil",
                        value = uiState.metrics.successfulDownloads.toString(),
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = StatusConnected,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        label = "Download Gagal",
                        value = uiState.metrics.failedDownloads.toString(),
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = StatusDisconnected,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // App & Session Info
            item {
                TesterSectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Informasi Sesi & Aplikasi",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    HorizontalDivider(color = HairlineBorder)
                    KeyValueTelemetryRow(
                        label = "Versi Aplikasi",
                        value = "v${uiState.config.appVersion}",
                        monospacedValue = true
                    )
                    KeyValueTelemetryRow(
                        label = "Mode Aktif",
                        value = uiState.config.mode.label,
                        monospacedValue = true
                    )
                    KeyValueTelemetryRow(
                        label = "Kredensial Perangkat",
                        value = if (uiState.authSession.hasStoredCredentials) {
                            "Tersimpan aman (Keystore)"
                        } else if (uiState.config.bearerToken.isNotBlank()) {
                            "Sesi RAM aktif (tersembunyi)"
                        } else {
                            "Belum ditautkan"
                        },
                        monospacedValue = true
                    )
                }
            }

            // Primary Actions
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onStartNewTest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_new_test_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start New Test",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onTestApiConnection,
                        enabled = !uiState.isTestingConnection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("test_api_connection_button"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        if (uiState.isTestingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Test API Connection",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun EndpointDiagnosticsBlock(
    summary: EndpointDiagnosticsSummary
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GET ${summary.endpointPath}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = JetBrainsMonoFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "OK ${summary.successCount} / Fail ${summary.failureCount}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFamily
                    ),
                    color = if (summary.failureCount == 0) StatusConnected else StatusDisconnected
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Min: ${summary.minLatencyMs?.let { "${it}ms" } ?: "-"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Avg: ${summary.avgLatencyMs?.let { "${it}ms" } ?: "-"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Max: ${summary.maxLatencyMs?.let { "${it}ms" } ?: "-"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = HairlineBorder)

            // Compact monospaced table header
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "#",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.55f)
                )
                Text(
                    text = "HTTP",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.95f)
                )
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.1f)
                )
                Text(
                    text = "DNS",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "TCP",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "TLS",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            summary.samples.forEach { sample ->
                LatencySampleRow(sample = sample)
            }
        }
    }
}

@Composable
private fun LatencySampleRow(sample: NetworkLatencySample) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${sample.requestIndex}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.55f)
            )
            Text(
                text = sample.httpStatusCode?.toString() ?: "ERR",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = if (sample.isSuccess) StatusConnected else StatusDisconnected,
                modifier = Modifier.weight(0.95f)
            )
            Text(
                text = "${sample.totalDurationMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1.1f)
            )
            Text(
                text = sample.dnsLookupMs?.let { "${it}ms" } ?: "-",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = sample.tcpConnectMs?.let { "${it}ms" } ?: "-",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = sample.tlsHandshakeMs?.let { "${it}ms" } ?: "-",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        if (!sample.errorMessage.isNullOrBlank()) {
            Text(
                text = sample.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = StatusDisconnected
            )
        }
    }
}
