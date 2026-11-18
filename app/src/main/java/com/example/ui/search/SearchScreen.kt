package com.example.ui.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.config.TestMode
import com.example.data.model.SearchTrackItem
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.StatusPill
import com.example.ui.components.TrackArtwork
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer
import com.example.ui.theme.StatusTesting
import com.example.ui.theme.StatusTestingContainer

@Composable
fun SearchScreen(
    uiState: SearchUiState,
    activeMode: TestMode,
    onQueryChanged: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSelectTrack: (SearchTrackItem) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Pencarian Lagu Amazon",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Endpoint: POST /poc/v1/search (${activeMode.label})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (activeMode == TestMode.DEMO || uiState.isSimulated) {
                        StatusPill(
                            text = "Simulasi Demo",
                            dotColor = StatusTesting,
                            containerColor = StatusTestingContainer,
                            modifier = Modifier.testTag("search_demo_badge")
                        )
                    } else {
                        StatusPill(
                            text = "Live API",
                            dotColor = StatusConnected,
                            containerColor = StatusConnectedContainer,
                            modifier = Modifier.testTag("search_live_badge")
                        )
                    }
                }
            }

            if (activeMode == TestMode.DEMO || uiState.isSimulated) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = StatusTestingContainer,
                        border = BorderStroke(1.dp, StatusTesting.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_simulation_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Science,
                                contentDescription = null,
                                tint = StatusTesting,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "DATA SIMULASI LOKAL (Demo Mode): Hasil di bawah tidak berasal dari server VPS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = onQueryChanged,
                        label = { Text("Nama lagu atau artis") },
                        placeholder = { Text("Contoh: Fly High") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_query_input")
                    )
                    Button(
                        onClick = onSearchSubmit,
                        enabled = !uiState.isLoading,
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("search_submit_button")
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Search")
                        }
                    }
                }
            }

            if (uiState.hasSearched || uiState.httpStatusCode != null || uiState.latencyMs != null) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.testTag("search_telemetry_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hasil: ${uiState.resultCount} lagu",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("search_result_count_text")
                            )
                            Text(
                                text = "HTTP ${uiState.httpStatusCode ?: "-"} • ${uiState.latencyMs ?: 0} ms",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (uiState.errorMessage != null) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("search_error_panel")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Gagal Mengambil Hasil Pencarian",
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

            if (!uiState.hasSearched && !uiState.isLoading) {
                item {
                    EmptyStatePanel(
                        title = "Siap Mencari Katalog",
                        subtitle = if (activeMode == TestMode.DEMO) {
                            "Masukkan kata kunci dan tekan Search untuk melihat contoh katalog lagu Demo Mode."
                        } else {
                            "Live Mode aktif: permintaan dikirim ke POST /poc/v1/search pada VPS terkonfigurasi."
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    )
                }
            } else if (uiState.hasSearched && uiState.results.isEmpty() && uiState.errorMessage == null && !uiState.isLoading) {
                item {
                    EmptyStatePanel(
                        title = "Tidak Ada Lagu Ditemukan",
                        subtitle = "Server merespons tanpa item hasil untuk kueri tersebut.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    )
                }
            }

            items(
                items = uiState.results,
                key = { "${it.trackUrl}_${it.title}" }
            ) { track ->
                SearchTrackCard(
                    track = track,
                    isSelected = track.trackUrl == uiState.selectedTrackUrl,
                    onSelect = { onSelectTrack(track) }
                )
            }
        }
    }
}

@Composable
private fun SearchTrackCard(
    track: SearchTrackItem,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else HairlineBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TrackArtwork(
                coverUrl = track.coverUrl,
                contentDescription = "Cover ${track.title}",
                size = 64.dp
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = track.albumDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "• ${track.formattedDuration}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Button(
                onClick = onSelect,
                modifier = Modifier.testTag("select_track_button_${track.title.take(12)}")
            ) {
                Text("Select")
            }
        }
    }
}
