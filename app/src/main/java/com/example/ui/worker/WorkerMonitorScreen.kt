package com.example.ui.worker

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.StatusHistoryEntry
import com.example.data.model.WorkerJobStatus
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KeyValueTelemetryRow
import com.example.ui.components.TesterSectionCard
import com.example.ui.components.WorkerStatusBadge
import com.example.ui.theme.HairlineBorder

@Composable
fun WorkerMonitorScreen(
    uiState: WorkerMonitorUiState,
    onStartPolling: () -> Unit,
    onStopPolling: () -> Unit,
    onPollOnce: () -> Unit,
    onGoToHandoff: () -> Unit,
    onGoToDirectDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ensure polling never continues running when user leaves this test screen
    DisposableEffect(Unit) {
        onDispose {
            onStopPolling()
        }
    }

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
                        text = "Monitor Lucida Worker",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Data Plane polling langsung ke worker (berhenti otomatis saat meninggalkan layar)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.activeHandoff == null) {
                item {
                    EmptyStatePanel(
                        title = "Belum Ada Pekerjaan Worker",
                        subtitle = "Buat handoff di layar Handoff terlebih dahulu untuk memantau status pekerjaan worker.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    )
                }
                item {
                    OutlinedButton(
                        onClick = onGoToHandoff,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Kembali ke Handoff")
                    }
                }
            } else {
                item {
                    TesterSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Status Pekerjaan",
                                style = MaterialTheme.typography.titleMedium
                            )
                            WorkerStatusBadge(
                                status = uiState.jobStatus,
                                modifier = Modifier.testTag("badge_worker_job_status")
                            )
                        }

                        HorizontalDivider(color = HairlineBorder)

                        KeyValueTelemetryRow(
                            label = "Worker Server",
                            value = uiState.workerServer,
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Status Pekerjaan",
                            value = uiState.jobStatus.wireValue,
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Waktu Polling Terakhir",
                            value = uiState.lastPollTimeFormatted,
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Durasi Proses",
                            value = formatDurationMs(uiState.elapsedProcessMs),
                            monospacedValue = true
                        )
                        if (uiState.lastMessage != null) {
                            KeyValueTelemetryRow(
                                label = "Catatan Worker",
                                value = uiState.lastMessage
                            )
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = uiState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
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
                        if (uiState.isPolling) {
                            Button(
                                onClick = onStopPolling,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("stop_polling_button")
                            ) {
                                Icon(imageVector = Icons.Outlined.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hentikan Polling")
                            }
                        } else {
                            Button(
                                onClick = onStartPolling,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("start_polling_button")
                            ) {
                                Icon(imageVector = Icons.Outlined.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Mulai Polling")
                            }
                        }

                        OutlinedButton(
                            onClick = onPollOnce,
                            enabled = !uiState.isPolling,
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("poll_once_button")
                        ) {
                            Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Poll Sekali")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sekali")
                        }
                    }
                }

                if (uiState.jobStatus == WorkerJobStatus.COMPLETED) {
                    item {
                        Button(
                            onClick = onGoToDirectDownload,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("go_to_direct_download_button")
                        ) {
                            Text("Lanjut ke Direct Download")
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "Riwayat Perubahan Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (uiState.history.isEmpty()) {
                    item {
                        TesterSectionCard {
                            Text(
                                text = "Belum ada riwayat polling. Tekan Mulai Polling untuk memantau status worker.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(uiState.history) { entry ->
                        HistoryEntryRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(entry: StatusHistoryEntry) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkerStatusBadge(status = entry.status)
                Text(
                    text = "${entry.timestampFormatted} • HTTP ${entry.httpStatus ?: "-"} (${entry.latencyMs} ms)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}.${(ms % 1000) / 100}s"
}
