package com.example.ui.handoff

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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.security.LogSanitizer
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KeyValueTelemetryRow
import com.example.ui.components.TesterSectionCard
import com.example.ui.components.TrackArtwork
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer

@Composable
fun HandoffScreen(
    uiState: HandoffUiState,
    onCreateHandoff: () -> Unit,
    onGoToSearch: () -> Unit,
    onGoToWorkerMonitor: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                        text = "Pembuatan Handoff Worker",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Endpoint: POST /poc/v1/handoff",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.selectedTrack == null) {
                item {
                    EmptyStatePanel(
                        title = "Belum Ada Lagu Dipilih",
                        subtitle = "Pilih lagu terlebih dahulu di halaman Search untuk membuat handoff ke Lucida Worker.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    )
                }
                item {
                    OutlinedButton(
                        onClick = onGoToSearch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Buka Pencarian Lagu")
                    }
                }
            } else {
                val track = uiState.selectedTrack
                item {
                    TesterSectionCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TrackArtwork(
                                coverUrl = track.coverUrl,
                                contentDescription = "Cover ${track.title}",
                                size = 56.dp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = HairlineBorder)

                        KeyValueTelemetryRow(
                            label = "Judul",
                            value = track.title
                        )
                        KeyValueTelemetryRow(
                            label = "Artis",
                            value = track.artist
                        )
                        KeyValueTelemetryRow(
                            label = "Album",
                            value = track.albumDisplay
                        )
                        KeyValueTelemetryRow(
                            label = "Durasi",
                            value = track.formattedDuration,
                            monospacedValue = true
                        )
                        KeyValueTelemetryRow(
                            label = "Track URL",
                            value = track.trackUrl,
                            monospacedValue = true
                        )
                    }
                }

                item {
                    Button(
                        onClick = onCreateHandoff,
                        enabled = !uiState.isCreating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("create_handoff_button")
                    ) {
                        if (uiState.isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = null
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Handoff")
                    }
                }

                if (uiState.errorMessage != null) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Gagal Membuat Handoff (HTTP ${uiState.httpStatusCode ?: "-"})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = uiState.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (uiState.activeHandoff != null) {
                    val handoff = uiState.activeHandoff
                    item {
                        TesterSectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Respons Handoff",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = StatusConnectedContainer,
                                    border = BorderStroke(1.dp, StatusConnected.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = handoff.status,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StatusConnected,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = HairlineBorder)

                            KeyValueTelemetryRow(
                                label = "Test ID",
                                value = handoff.testId,
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Handoff ID",
                                value = handoff.handoffId,
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Worker Server",
                                value = "${handoff.server} (${handoff.country})",
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Poll Target (Disamarkan)",
                                value = LogSanitizer.maskUrlForDisplay(handoff.pollUrl),
                                monospacedValue = true
                            )
                            KeyValueTelemetryRow(
                                label = "Download Target (Disamarkan)",
                                value = LogSanitizer.maskUrlForDisplay(handoff.downloadUrl),
                                monospacedValue = true
                            )
                            if (uiState.latencyMs != null) {
                                KeyValueTelemetryRow(
                                    label = "Latensi Control Plane",
                                    value = "${uiState.latencyMs} ms (HTTP ${uiState.httpStatusCode ?: "-"})",
                                    monospacedValue = true
                                )
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = onGoToWorkerMonitor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("go_to_worker_monitor_button")
                        ) {
                            Text("Pantau Lucida Worker")
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
        }
    }
}
