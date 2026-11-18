package com.example.ui.download

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadStage
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KeyValueTelemetryRow
import com.example.ui.components.TesterSectionCard
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusDisconnectedContainer

@Composable
fun DirectDownloadScreen(
    uiState: DirectDownloadUiState,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onExportSafUri: (android.net.Uri) -> Unit,
    onSendDiagnosticReport: () -> Unit,
    onGoToWorkerMonitor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/flac")
    ) { uri ->
        if (uri != null) {
            onExportSafUri(uri)
        }
    }

    val progress = uiState.progress
    val isDownloading = progress.stage == DownloadStage.CONNECTING ||
        progress.stage == DownloadStage.DOWNLOADING ||
        progress.stage == DownloadStage.VALIDATING

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Direct Download Worker",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Transfer audio langsung dari worker via OkHttp tanpa melewati VPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.activeHandoff == null || !uiState.downloadUrlReady) {
                item {
                    EmptyStatePanel(
                        title = "Belum Ada Target Download Worker",
                        subtitle = "Selesaikan handoff dan polling status worker hingga siap sebelum memulai unduhan langsung.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    )
                }
                item {
                    OutlinedButton(
                        onClick = onGoToWorkerMonitor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Buka Monitor Worker")
                    }
                }
            } else {
                val handoff = uiState.activeHandoff
                item {
                    TesterSectionCard {
                        Text(
                            text = progress.trackTitle.ifBlank { handoff.title },
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = progress.trackArtist.ifBlank { handoff.artist },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(color = HairlineBorder)

                        KeyValueTelemetryRow(
                            label = "Status Download",
                            value = progress.stage.label
                        )
                        KeyValueTelemetryRow(
                            label = "Worker Sumber",
                            value = handoff.server,
                            monospacedValue = true
                        )

                        if (progress.stage == DownloadStage.DOWNLOADING ||
                            progress.stage == DownloadStage.CONNECTING ||
                            progress.stage == DownloadStage.COMPLETED
                        ) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val total = progress.totalBytes
                            if (progress.stage == DownloadStage.CONNECTING) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                            } else if (total != null && total > 0L) {
                                val fraction = (progress.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                            }
                        }

                        KeyValueTelemetryRow(
                            label = "Ukuran Diterima",
                            value = formatBytes(progress.bytesDownloaded),
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Total Ukuran File",
                            value = progress.totalBytes?.let { formatBytes(it) } ?: "Tidak tersedia",
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Kecepatan Download",
                            value = "${formatBytes(progress.speedBytesPerSec)}/s",
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Waktu Berlalu",
                            value = formatElapsed(progress.elapsedMillis),
                            monospacedValue = true
                        )
                    }
                }

                if (progress.errorMessage != null) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = StatusDisconnectedContainer,
                            border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = progress.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onStartDownload,
                            enabled = !isDownloading,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("start_direct_download_button")
                        ) {
                            Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (progress.stage == DownloadStage.COMPLETED) "Unduh Ulang" else "Mulai Download"
                            )
                        }

                        OutlinedButton(
                            onClick = onCancelDownload,
                            enabled = isDownloading,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = StatusDisconnected
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isDownloading) StatusDisconnected else HairlineBorder
                            ),
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("cancel_download_button")
                        ) {
                            Icon(imageVector = Icons.Outlined.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    }
                }

                val validation = progress.validationResult
                if (validation != null) {
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
                                        imageVector = Icons.Outlined.VerifiedUser,
                                        contentDescription = null,
                                        tint = if (validation.isOverallValid) StatusConnected else StatusDisconnected
                                    )
                                    Text(
                                        text = "Hasil Validasi File FLAC",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (validation.isOverallValid) StatusConnectedContainer else StatusDisconnectedContainer
                                ) {
                                    Text(
                                        text = if (validation.isOverallValid) "VALID" else "GAGAL",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (validation.isOverallValid) StatusConnected else StatusDisconnected,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = HairlineBorder)

                            KeyValueTelemetryRow(
                                label = "HTTP Response",
                                value = "${validation.httpStatusCode ?: "-"} (${if (validation.httpStatusValid) "OK" else "Tidak Valid"})",
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Ukuran File",
                                value = "${formatBytes(validation.fileSizeBytes)} (${validation.fileSizeBytes} B)",
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Ekstensi & Signature FLAC",
                                value = "Ext .flac: ${if (validation.hasValidExtension) "Ya" else "Tidak"} • Magic fLaC: ${if (validation.hasFlacSignature) "Valid" else "Tidak Valid"}",
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "STREAMINFO Dasar",
                                value = validation.streamInfoSummary,
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Metadata",
                                value = validation.metadataSummary
                            )
                            KeyValueTelemetryRow(
                                label = "Cover Artwork",
                                value = validation.coverArtworkSummary
                            )
                            KeyValueTelemetryRow(
                                label = "SHA-256 (Fingerprint Lokal)",
                                value = validation.sha256Fingerprint,
                                monospacedValue = true
                            )

                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Catatan: SHA-256 hanya fingerprint file lokal untuk identifikasi berkas, bukan bukti file berasal dari sumber tepercaya dan bukan verifikasi STREAMINFO MD5 audio.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            if (progress.exportedLocationNote != null) {
                                Text(
                                    text = progress.exportedLocationNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusConnected
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { safLauncher.launch(progress.suggestedFileName) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("saf_save_button")
                                ) {
                                    Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Simpan SAF")
                                }

                                OutlinedButton(
                                    onClick = onSendDiagnosticReport,
                                    enabled = uiState.config.diagnosticReportContractVerified && !uiState.isSendingReport,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("send_report_button")
                                ) {
                                    if (uiState.isSendingReport) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(imageVector = Icons.Outlined.Send, contentDescription = null)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Kirim Report")
                                }
                            }

                            if (!uiState.config.diagnosticReportContractVerified) {
                                Text(
                                    text = "Pengiriman laporan diagnostik ke VPS dinonaktifkan sampai kontrak endpoint report diverifikasi di Pengaturan Koneksi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (uiState.reportStatusMessage != null) {
                                Text(
                                    text = uiState.reportStatusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.2f MB", mb)
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "${min}m ${sec}s" else "${sec}.${tenths}s"
}
