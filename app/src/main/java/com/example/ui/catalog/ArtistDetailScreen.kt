package com.example.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.example.ui.theme.HairlineBorder

@Composable
fun ArtistDetailScreen(
    uiState: ArtistDetailUiState,
    onBack: () -> Unit,
    onSyncAlbums: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onRetry: () -> Unit,
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
                .testTag("artist_detail_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "artist_top_row") {
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
                            modifier = Modifier.testTag("artist_detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Kembali"
                            )
                        }
                        Text(
                            text = "Artist Detail",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    if (uiState.artist != null) {
                        OutlinedButton(
                            onClick = onSyncAlbums,
                            enabled = !uiState.isSyncing,
                            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("artist_sync_albums_button")
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
                                text = if (uiState.isSyncing) "Syncing..." else "Sync Albums",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            item(key = "artist_loading_bar") {
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
                item(key = "artist_sync_notice") {
                    CatalogNoticeBanner(message = uiState.syncNotice)
                }
            }

            if (uiState.errorMessage != null) {
                item(key = "artist_error_banner") {
                    CatalogErrorBanner(
                        message = uiState.errorMessage,
                        onRetry = onRetry
                    )
                }
            }

            val artist = uiState.artist
            if (artist != null) {
                item(key = "artist_profile_card") {
                    TesterSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (artist.artworkUrl != null) {
                                TrackArtwork(
                                    coverUrl = artist.artworkUrl,
                                    contentDescription = artist.name,
                                    size = 76.dp
                                )
                            } else {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, HairlineBorder),
                                    modifier = Modifier.size(76.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Outlined.Person,
                                            contentDescription = null,
                                            tint = CyanAccent,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("artist_detail_name")
                                )
                                val subtitle = listOfNotNull(
                                    artist.primaryGenre,
                                    artist.country
                                ).joinToString(" • ")
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (artist.primaryGenre != null) {
                            KeyValueTelemetryRow(label = "Genre", value = artist.primaryGenre)
                        }
                        if (artist.country != null) {
                            KeyValueTelemetryRow(label = "Country", value = artist.country)
                        }
                        if (artist.musicBrainzArtistId != null) {
                            KeyValueTelemetryRow(
                                label = "MusicBrainz ID",
                                value = artist.musicBrainzArtistId,
                                monospacedValue = true
                            )
                        }
                    }
                }

                item(key = "artist_discography_header") {
                    CatalogSectionHeader(
                        title = "Discography",
                        totalCount = uiState.totalAlbums
                    )
                }

                if (uiState.albums.isEmpty() && !uiState.isLoading) {
                    item(key = "artist_empty_discography") {
                        EmptyStatePanel(
                            title = "Belum ada album di katalog lokal",
                            subtitle = "Tekan tombol Sync Albums di atas untuk mengimpor diskografi artis ini dari Apple iTunes.",
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Album,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp)
                                )
                            },
                            modifier = Modifier.testTag("artist_empty_discography")
                        )
                    }
                } else {
                    items(
                        items = uiState.albums,
                        key = { "artist_album_${it.id}" }
                    ) { album ->
                        CatalogAlbumRow(
                            album = album,
                            onClick = { onAlbumClick(album.id) }
                        )
                    }
                    if (uiState.hasMoreAlbums) {
                        item(key = "artist_albums_load_more") {
                            CatalogLoadMoreRow(
                                isLoadingMore = uiState.isLoadingMore,
                                loadedCount = uiState.albums.size,
                                totalCount = uiState.totalAlbums,
                                onLoadMore = onLoadMoreAlbums
                            )
                        }
                    }
                }
            } else if (!uiState.isLoading && uiState.errorMessage == null) {
                item(key = "artist_not_found") {
                    EmptyStatePanel(
                        title = "Artis tidak tersedia",
                        subtitle = "Data artis tidak ditemukan di database katalog lokal.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Person,
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
