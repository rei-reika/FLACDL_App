package com.example.catalog.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artists",
    indices = [
        Index(value = ["provider", "provider_artist_id"], unique = true),
        Index(value = ["name_normalized"]),
        Index(value = ["musicbrainz_artist_id"])
    ]
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "provider_artist_id")
    val providerArtistId: String,
    @ColumnInfo(name = "musicbrainz_artist_id")
    val musicBrainzArtistId: String? = null,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "name_normalized")
    val nameNormalized: String,
    @ColumnInfo(name = "primary_genre")
    val primaryGenre: String? = null,
    @ColumnInfo(name = "country")
    val country: String? = null,
    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long
)

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider", "provider_album_id"], unique = true),
        Index(value = ["artist_id"]),
        Index(value = ["title_normalized"]),
        Index(value = ["musicbrainz_release_group_id"])
    ]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "provider_album_id")
    val providerAlbumId: String,
    @ColumnInfo(name = "musicbrainz_release_group_id")
    val musicBrainzReleaseGroupId: String? = null,
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "title_normalized")
    val titleNormalized: String,
    @ColumnInfo(name = "release_date")
    val releaseDate: String? = null,
    @ColumnInfo(name = "genre")
    val genre: String? = null,
    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,
    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider", "provider_track_id"], unique = true),
        Index(value = ["album_id"]),
        Index(value = ["artist_id"]),
        Index(value = ["title_normalized"]),
        Index(value = ["musicbrainz_recording_id"]),
        Index(value = ["isrc"])
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "provider_track_id")
    val providerTrackId: String,
    @ColumnInfo(name = "musicbrainz_recording_id")
    val musicBrainzRecordingId: String? = null,
    @ColumnInfo(name = "album_id")
    val albumId: Long? = null,
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "title_normalized")
    val titleNormalized: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int? = null,
    @ColumnInfo(name = "disc_number")
    val discNumber: Int? = null,
    @ColumnInfo(name = "isrc")
    val isrc: String? = null,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long
)

@Entity(
    tableName = "catalog_sync",
    indices = [
        Index(value = ["provider", "entity_type", "query_normalized"], unique = true)
    ]
)
data class CatalogSyncEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "query_normalized")
    val queryNormalized: String,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long,
    @ColumnInfo(name = "result_count")
    val resultCount: Int,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String
)

@Entity(
    tableName = "catalog_cache",
    indices = [
        Index(value = ["expires_at"])
    ]
)
data class CatalogCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "response_json")
    val responseJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long
)
