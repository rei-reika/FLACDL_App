package com.example.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.KeyValueTelemetryRow
import com.example.ui.components.TesterSectionCard
import com.example.ui.components.TrackArtwork
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun AlbumDetailScreen(
    uiState: AlbumDetailUiState,
    onBack: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onSyncTracks: () -> Unit,
    onLoadMoreTracks: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distinctDiscs = uiState.tracks.mapNotNull { it.discNumber }.distinct()
    val showDiscHeaders = distinctDiscs.size > 1

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("album_detail_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "album_top_row") {
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
                            modifier = Modifier.testTag("album_detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Kembali"
                            )
                        }
                        Text(
                            text = "Album Detail",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    if (uiState.album != null) {
                        OutlinedButton(
                            onClick = onSyncTracks,
                            enabled = !uiState.isSyncing,
                            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("album_sync_tracks_button")
                        ) {
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = CyanAccent
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isSyncing) "Syncing..." else "Sync Tracklist",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            item(key = "album_loading_bar") {
                AnimatedVisibility(
                    visible = uiState.isLoading || uiState.isSyncing,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = CyanAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            if (uiState.syncNotice != null) {
                item(key = "album_sync_notice") {
                    CatalogNoticeBanner(message = uiState.syncNotice)
                }
            }

            if (uiState.errorMessage != null) {
                item(key = "album_error_banner") {
                    CatalogErrorBanner(
                        message = uiState.errorMessage,
                        onRetry = onRetry
                    )
                }
            }

            val album = uiState.album
            if (album != null) {
                item(key = "album_header_card") {
                    TesterSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TrackArtwork(
                                coverUrl = album.artworkUrl,
                                contentDescription = album.title,
                                size = 88.dp
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("album_detail_title")
                                )
                                Text(
                                    text = album.artistName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CyanAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clickable { onArtistClick(album.artistId) }
                                        .testTag("album_detail_artist_link")
                                )
                                val metaLine = listOfNotNull(
                                    album.releaseYear,
                                    album.trackCount?.let { "$it tracks" }
                                ).joinToString(" • ")
                                if (metaLine.isNotEmpty()) {
                                    Text(
                                        text = metaLine,
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (album.releaseDate != null) {
                            KeyValueTelemetryRow(
                                label = "Release Date",
                                value = album.releaseDate,
                                monospacedValue = true
                            )
                        }
                        if (album.trackCount != null) {
                            KeyValueTelemetryRow(
                                label = "Track Count",
                                value = album.trackCount.toString(),
                                monospacedValue = true
                            )
                        }
                        if (album.genre != null) {
                            KeyValueTelemetryRow(label = "Genre", value = album.genre)
                        }
                    }
                }

                item(key = "album_tracklist_header") {
                    CatalogSectionHeader(
                        title = "Tracklist",
                        totalCount = uiState.totalTracks
                    )
                }

                if (uiState.tracks.isEmpty() && !uiState.isLoading) {
                    item(key = "album_empty_tracklist") {
                        EmptyStatePanel(
                            title = "Tracklist belum tersedia di katalog lokal",
                            subtitle = "Tekan tombol Sync Tracklist di atas untuk mengambil daftar lagu album ini dari Apple iTunes.",
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp)
                                )
                            },
                            modifier = Modifier.testTag("album_empty_tracklist")
                        )
                    }
                } else {
                    if (showDiscHeaders) {
                        val grouped = uiState.tracks.groupBy { it.discNumber ?: 1 }
                        grouped.forEach { (discNum, discTracks) ->
                            item(key = "disc_header_$discNum") {
                                Text(
                                    text = "Disc $discNum",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                    color = CyanAccent,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                            }
                            items(
                                items = discTracks,
                                key = { "album_track_${it.id}" }
                            ) { track ->
                                CatalogTrackRow(
                                    track = track,
                                    showTrackNumber = true
                                )
                            }
                        }
                    } else {
                        items(
                            items = uiState.tracks,
                            key = { "album_track_${it.id}" }
                        ) { track ->
                            CatalogTrackRow(
                                track = track,
                                showTrackNumber = true
                            )
                        }
                    }

                    if (uiState.hasMoreTracks) {
                        item(key = "album_tracks_load_more") {
                            CatalogLoadMoreRow(
                                isLoadingMore = uiState.isLoadingMore,
                                loadedCount = uiState.tracks.size,
                                totalCount = uiState.totalTracks,
                                onLoadMore = onLoadMoreTracks
                            )
                        }
                    }
                }
            } else if (!uiState.isLoading && uiState.errorMessage == null) {
                item(key = "album_not_found") {
                    EmptyStatePanel(
                        title = "Album tidak tersedia",
                        subtitle = "Data album tidak ditemukan di database katalog lokal.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
