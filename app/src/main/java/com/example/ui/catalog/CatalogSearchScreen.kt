package com.example.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.StatusPill
import com.example.ui.components.TrackArtwork
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanMutedContainer
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusDisconnectedContainer

@Composable
fun CatalogSearchScreen(
    uiState: CatalogSearchUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onCategorySelected: (CatalogSearchCategory) -> Unit,
    onSearchSubmit: () -> Unit,
    onSyncQuery: () -> Unit,
    onLoadMore: (CatalogSearchCategory) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
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
                .testTag("catalog_search_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "catalog_header") {
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
                            text = "Local Catalog",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Pencarian offline-first dari database Room lokal",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        text = "Room Local",
                        dotColor = CyanAccent,
                        containerColor = CyanMutedContainer,
                        modifier = Modifier.testTag("catalog_room_badge")
                    )
                }
            }

            item(key = "catalog_search_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = onQueryChanged,
                        label = { Text("Cari artis, album, atau lagu") },
                        placeholder = { Text("Contoh: Aimer, Ado, Yoasobi") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearQuery,
                                    modifier = Modifier.testTag("catalog_clear_query_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Clear,
                                        contentDescription = "Hapus kata kunci"
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("catalog_query_input")
                    )
                    OutlinedButton(
                        onClick = onSyncQuery,
                        enabled = uiState.query.trim().isNotEmpty() && !uiState.isSyncing,
                        border = BorderStroke(
                            1.dp,
                            if (uiState.query.trim().isNotEmpty()) CyanAccent.copy(alpha = 0.7f) else HairlineBorder
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("catalog_sync_button")
                    ) {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CyanAccent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = "Sync dari Apple iTunes",
                                tint = CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isSyncing) "Sync" else "Import",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            item(key = "catalog_category_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CatalogSearchCategory.entries.forEach { category ->
                        val count = when (category) {
                            CatalogSearchCategory.ALL -> null
                            CatalogSearchCategory.ARTISTS -> uiState.artistsTotal.takeIf { uiState.hasSearched && it > 0 }
                            CatalogSearchCategory.ALBUMS -> uiState.albumsTotal.takeIf { uiState.hasSearched && it > 0 }
                            CatalogSearchCategory.TRACKS -> uiState.tracksTotal.takeIf { uiState.hasSearched && it > 0 }
                        }
                        val label = if (count != null) "${category.label} ($count)" else category.label
                        FilterChip(
                            selected = uiState.selectedCategory == category,
                            onClick = { onCategorySelected(category) },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanMutedContainer,
                                selectedLabelColor = CyanAccent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = uiState.selectedCategory == category,
                                borderColor = HairlineBorder,
                                selectedBorderColor = CyanAccent.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.testTag("catalog_chip_${category.name.lowercase()}")
                        )
                    }
                }
            }

            item(key = "catalog_progress") {
                AnimatedVisibility(
                    visible = uiState.isLoading || uiState.isSyncing,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .testTag("catalog_loading_bar"),
                        color = CyanAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            if (uiState.syncNotice != null) {
                item(key = "catalog_sync_notice") {
                    CatalogNoticeBanner(message = uiState.syncNotice)
                }
            }

            if (uiState.errorMessage != null) {
                item(key = "catalog_error_banner") {
                    CatalogErrorBanner(
                        message = uiState.errorMessage,
                        onRetry = onSearchSubmit
                    )
                }
            }

            if (!uiState.hasSearched && !uiState.isLoading) {
                item(key = "catalog_idle_state") {
                    EmptyStatePanel(
                        title = "Jelajahi Katalog Lokal",
                        subtitle = "Ketik nama artis, album, atau lagu untuk mencari di database Room lokal. Gunakan tombol Import jika ingin mengambil metadata baru dari Apple iTunes.",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.LibraryMusic,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(30.dp)
                            )
                        },
                        modifier = Modifier.testTag("catalog_idle_panel")
                    )
                }
            } else if (uiState.hasSearched && !uiState.isLoading && uiState.isEmptyResults) {
                item(key = "catalog_empty_results") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("catalog_empty_panel")
                    ) {
                        EmptyStatePanel(
                            title = "Belum ada hasil di katalog lokal",
                            subtitle = "Kata kunci \"${uiState.query.trim()}\" belum ditemukan di database Room. Tekan tombol di bawah untuk mengimpor metadata dari Apple iTunes.",
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        )
                        OutlinedButton(
                            onClick = onSyncQuery,
                            enabled = !uiState.isSyncing,
                            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.7f)),
                            modifier = Modifier.testTag("catalog_empty_import_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import \"${uiState.query.trim()}\" dari iTunes")
                        }
                    }
                }
            } else {
                val showArtists = uiState.selectedCategory == CatalogSearchCategory.ALL ||
                    uiState.selectedCategory == CatalogSearchCategory.ARTISTS
                val showAlbums = uiState.selectedCategory == CatalogSearchCategory.ALL ||
                    uiState.selectedCategory == CatalogSearchCategory.ALBUMS
                val showTracks = uiState.selectedCategory == CatalogSearchCategory.ALL ||
                    uiState.selectedCategory == CatalogSearchCategory.TRACKS

                if (showArtists && uiState.artists.isNotEmpty()) {
                    item(key = "section_artists_header") {
                        CatalogSectionHeader(
                            title = "Artists",
                            totalCount = uiState.artistsTotal,
                            showSeeAll = uiState.selectedCategory == CatalogSearchCategory.ALL && uiState.hasMoreArtists,
                            onSeeAll = { onCategorySelected(CatalogSearchCategory.ARTISTS) }
                        )
                    }
                    items(
                        items = uiState.artists,
                        key = { "artist_${it.id}" }
                    ) { artist ->
                        CatalogArtistRow(
                            artist = artist,
                            onClick = { onArtistClick(artist.id) }
                        )
                    }
                    if (uiState.selectedCategory == CatalogSearchCategory.ARTISTS && uiState.hasMoreArtists) {
                        item(key = "load_more_artists") {
                            CatalogLoadMoreRow(
                                isLoadingMore = uiState.isLoadingMore,
                                loadedCount = uiState.artists.size,
                                totalCount = uiState.artistsTotal,
                                onLoadMore = { onLoadMore(CatalogSearchCategory.ARTISTS) }
                            )
                        }
                    }
                }

                if (showAlbums && uiState.albums.isNotEmpty()) {
                    item(key = "section_albums_header") {
                        CatalogSectionHeader(
                            title = "Albums",
                            totalCount = uiState.albumsTotal,
                            showSeeAll = uiState.selectedCategory == CatalogSearchCategory.ALL && uiState.hasMoreAlbums,
                            onSeeAll = { onCategorySelected(CatalogSearchCategory.ALBUMS) }
                        )
                    }
                    items(
                        items = uiState.albums,
                        key = { "album_${it.id}" }
                    ) { album ->
                        CatalogAlbumRow(
                            album = album,
                            onClick = { onAlbumClick(album.id) }
                        )
                    }
                    if (uiState.selectedCategory == CatalogSearchCategory.ALBUMS && uiState.hasMoreAlbums) {
                        item(key = "load_more_albums") {
                            CatalogLoadMoreRow(
                                isLoadingMore = uiState.isLoadingMore,
                                loadedCount = uiState.albums.size,
                                totalCount = uiState.albumsTotal,
                                onLoadMore = { onLoadMore(CatalogSearchCategory.ALBUMS) }
                            )
                        }
                    }
                }

                if (showTracks && uiState.tracks.isNotEmpty()) {
                    item(key = "section_tracks_header") {
                        CatalogSectionHeader(
                            title = "Tracks",
                            totalCount = uiState.tracksTotal,
                            showSeeAll = uiState.selectedCategory == CatalogSearchCategory.ALL && uiState.hasMoreTracks,
                            onSeeAll = { onCategorySelected(CatalogSearchCategory.TRACKS) }
                        )
                    }
                    items(
                        items = uiState.tracks,
                        key = { "track_${it.id}" }
                    ) { track ->
                        CatalogTrackRow(
                            track = track,
                            onAlbumClick = track.albumId?.let { id -> { onAlbumClick(id) } }
                        )
                    }
                    if (uiState.selectedCategory == CatalogSearchCategory.TRACKS && uiState.hasMoreTracks) {
                        item(key = "load_more_tracks") {
                            CatalogLoadMoreRow(
                                isLoadingMore = uiState.isLoadingMore,
                                loadedCount = uiState.tracks.size,
                                totalCount = uiState.tracksTotal,
                                onLoadMore = { onLoadMore(CatalogSearchCategory.TRACKS) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CatalogSectionHeader(
    title: String,
    totalCount: Int,
    showSeeAll: Boolean = false,
    onSeeAll: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = totalCount.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showSeeAll && onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(
                    text = "Lihat semua",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanAccent
                )
            }
        }
    }
}

@Composable
internal fun CatalogArtistRow(
    artist: CatalogArtistUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("catalog_artist_row_${artist.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (artist.artworkUrl != null) {
                TrackArtwork(
                    coverUrl = artist.artworkUrl,
                    contentDescription = artist.name,
                    size = 48.dp
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = listOfNotNull(artist.primaryGenre, artist.country).joinToString(" • ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Buka detail artis",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun CatalogAlbumRow(
    album: CatalogAlbumUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("catalog_album_row_${album.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TrackArtwork(
                coverUrl = album.artworkUrl,
                contentDescription = album.title,
                size = 54.dp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    album.releaseYear,
                    album.trackCount?.let { "$it tracks" },
                    album.genre
                ).joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Buka detail album",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Track result row for Local Catalog UI.
 * Intentionally NOT connected to download or playlists in Phase 2C.1.
 */
@Composable
internal fun CatalogTrackRow(
    track: CatalogTrackUi,
    onAlbumClick: (() -> Unit)? = null,
    showTrackNumber: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showTrackNumber) {
                Box(
                    modifier = Modifier.width(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = track.trackNumber?.toString() ?: "–",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (track.artworkUrl != null) {
                TrackArtwork(
                    coverUrl = track.artworkUrl,
                    contentDescription = track.title,
                    size = 46.dp
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = listOfNotNull(
                    track.artistName.takeIf { it.isNotBlank() },
                    track.albumTitle?.takeIf { !showTrackNumber }
                ).joinToString(" • ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (track.durationText != null) {
                Text(
                    text = track.durationText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (onAlbumClick != null) {
        Card(
            onClick = onAlbumClick,
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = modifier
                .fillMaxWidth()
                .testTag("catalog_track_row_${track.id}")
        ) {
            cardContent()
        }
    } else {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = modifier
                .fillMaxWidth()
                .testTag("catalog_track_row_${track.id}")
        ) {
            cardContent()
        }
    }
}

@Composable
internal fun CatalogLoadMoreRow(
    isLoadingMore: Boolean,
    loadedCount: Int,
    totalCount: Int,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(
            onClick = onLoadMore,
            enabled = !isLoadingMore,
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier.testTag("catalog_load_more_button")
        ) {
            if (isLoadingMore) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = CyanAccent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Memuat...")
            } else {
                Text("Muat lebih banyak ($loadedCount / $totalCount)")
            }
        }
    }
}

@Composable
internal fun CatalogNoticeBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = StatusConnectedContainer,
        border = BorderStroke(1.dp, StatusConnected.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("catalog_notice_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = StatusConnected,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun CatalogErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = StatusDisconnectedContainer,
        border = BorderStroke(1.dp, StatusDisconnected.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("catalog_error_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = StatusDisconnected,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(
                        text = "Coba lagi",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanAccent
                    )
                }
            }
        }
    }
}
