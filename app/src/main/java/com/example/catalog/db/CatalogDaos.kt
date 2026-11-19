package com.example.catalog.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

data class AlbumWithArtistRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "provider_album_id") val providerAlbumId: String,
    @ColumnInfo(name = "musicbrainz_release_group_id") val musicBrainzReleaseGroupId: String?,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "genre") val genre: String?,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long
)

data class TrackWithRelationsRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "provider_track_id") val providerTrackId: String,
    @ColumnInfo(name = "musicbrainz_recording_id") val musicBrainzRecordingId: String?,
    @ColumnInfo(name = "album_id") val albumId: Long?,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "album_artwork_url") val albumArtworkUrl: String?,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "disc_number") val discNumber: Int?,
    @ColumnInfo(name = "isrc") val isrc: String?,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long
)

@Dao
interface ArtistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ArtistEntity): Long

    @Update
    suspend fun update(entity: ArtistEntity): Int

    @Query("SELECT * FROM artists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE provider = :provider AND provider_artist_id = :providerArtistId LIMIT 1")
    suspend fun getByProviderId(provider: String, providerArtistId: String): ArtistEntity?

    @Query(
        """
        SELECT * FROM artists
        WHERE name_normalized LIKE '%' || :normalizedQuery || '%'
        ORDER BY CASE WHEN name_normalized = :normalizedQuery THEN 0
                      WHEN name_normalized LIKE :normalizedQuery || '%' THEN 1
                      ELSE 2 END,
                 name_normalized ASC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByName(normalizedQuery: String, limit: Int, offset: Int): List<ArtistEntity>

    @Query("SELECT COUNT(*) FROM artists WHERE name_normalized LIKE '%' || :normalizedQuery || '%'")
    suspend fun countSearchByName(normalizedQuery: String): Int

    @Query("SELECT COUNT(*) FROM artists")
    suspend fun countAll(): Int
}

@Dao
interface AlbumDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AlbumEntity): Long

    @Update
    suspend fun update(entity: AlbumEntity): Int

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun getEntityById(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE provider = :provider AND provider_album_id = :providerAlbumId LIMIT 1")
    suspend fun getByProviderId(provider: String, providerAlbumId: String): AlbumEntity?

    @Query(
        """
        SELECT al.id, al.provider_album_id, al.musicbrainz_release_group_id, al.artist_id,
               ar.name AS artist_name, al.title, al.release_date, al.genre, al.artwork_url,
               al.track_count, al.provider, al.last_synced_at
        FROM albums al
        INNER JOIN artists ar ON ar.id = al.artist_id
        WHERE al.id = :albumId
        LIMIT 1
        """
    )
    suspend fun getDetailsById(albumId: Long): AlbumWithArtistRow?

    @Query(
        """
        SELECT al.id, al.provider_album_id, al.musicbrainz_release_group_id, al.artist_id,
               ar.name AS artist_name, al.title, al.release_date, al.genre, al.artwork_url,
               al.track_count, al.provider, al.last_synced_at
        FROM albums al
        INNER JOIN artists ar ON ar.id = al.artist_id
        WHERE al.title_normalized LIKE '%' || :normalizedQuery || '%'
        ORDER BY CASE WHEN al.title_normalized = :normalizedQuery THEN 0
                      WHEN al.title_normalized LIKE :normalizedQuery || '%' THEN 1
                      ELSE 2 END,
                 al.release_date DESC, al.title_normalized ASC, al.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByTitle(normalizedQuery: String, limit: Int, offset: Int): List<AlbumWithArtistRow>

    @Query("SELECT COUNT(*) FROM albums WHERE title_normalized LIKE '%' || :normalizedQuery || '%'")
    suspend fun countSearchByTitle(normalizedQuery: String): Int

    @Query(
        """
        SELECT al.id, al.provider_album_id, al.musicbrainz_release_group_id, al.artist_id,
               ar.name AS artist_name, al.title, al.release_date, al.genre, al.artwork_url,
               al.track_count, al.provider, al.last_synced_at
        FROM albums al
        INNER JOIN artists ar ON ar.id = al.artist_id
        WHERE ar.name_normalized LIKE '%' || :normalizedArtist || '%'
        ORDER BY al.release_date DESC, al.title_normalized ASC, al.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByArtistName(normalizedArtist: String, limit: Int, offset: Int): List<AlbumWithArtistRow>

    @Query(
        """
        SELECT COUNT(*) FROM albums al
        INNER JOIN artists ar ON ar.id = al.artist_id
        WHERE ar.name_normalized LIKE '%' || :normalizedArtist || '%'
        """
    )
    suspend fun countSearchByArtistName(normalizedArtist: String): Int

    @Query(
        """
        SELECT al.id, al.provider_album_id, al.musicbrainz_release_group_id, al.artist_id,
               ar.name AS artist_name, al.title, al.release_date, al.genre, al.artwork_url,
               al.track_count, al.provider, al.last_synced_at
        FROM albums al
        INNER JOIN artists ar ON ar.id = al.artist_id
        WHERE al.artist_id = :artistId
        ORDER BY al.release_date DESC, al.title_normalized ASC, al.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getDiscographyByArtistId(artistId: Long, limit: Int, offset: Int): List<AlbumWithArtistRow>

    @Query("SELECT COUNT(*) FROM albums WHERE artist_id = :artistId")
    suspend fun countByArtistId(artistId: Long): Int

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun countAll(): Int
}

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TrackEntity): Long

    @Update
    suspend fun update(entity: TrackEntity): Int

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getEntityById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE provider = :provider AND provider_track_id = :providerTrackId LIMIT 1")
    suspend fun getByProviderId(provider: String, providerTrackId: String): TrackEntity?

    @Query(
        """
        SELECT t.id, t.provider_track_id, t.musicbrainz_recording_id, t.album_id,
               al.title AS album_title, al.artwork_url AS album_artwork_url,
               t.artist_id, ar.name AS artist_name, t.title, t.duration_ms,
               t.track_number, t.disc_number, t.isrc, t.provider, t.last_synced_at
        FROM tracks t
        INNER JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.id = :trackId
        LIMIT 1
        """
    )
    suspend fun getDetailsById(trackId: Long): TrackWithRelationsRow?

    @Query(
        """
        SELECT t.id, t.provider_track_id, t.musicbrainz_recording_id, t.album_id,
               al.title AS album_title, al.artwork_url AS album_artwork_url,
               t.artist_id, ar.name AS artist_name, t.title, t.duration_ms,
               t.track_number, t.disc_number, t.isrc, t.provider, t.last_synced_at
        FROM tracks t
        INNER JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.title_normalized LIKE '%' || :normalizedQuery || '%'
        ORDER BY CASE WHEN t.title_normalized = :normalizedQuery THEN 0
                      WHEN t.title_normalized LIKE :normalizedQuery || '%' THEN 1
                      ELSE 2 END,
                 t.title_normalized ASC, t.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByTitle(normalizedQuery: String, limit: Int, offset: Int): List<TrackWithRelationsRow>

    @Query("SELECT COUNT(*) FROM tracks WHERE title_normalized LIKE '%' || :normalizedQuery || '%'")
    suspend fun countSearchByTitle(normalizedQuery: String): Int

    @Query(
        """
        SELECT t.id, t.provider_track_id, t.musicbrainz_recording_id, t.album_id,
               al.title AS album_title, al.artwork_url AS album_artwork_url,
               t.artist_id, ar.name AS artist_name, t.title, t.duration_ms,
               t.track_number, t.disc_number, t.isrc, t.provider, t.last_synced_at
        FROM tracks t
        INNER JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE ar.name_normalized LIKE '%' || :normalizedArtist || '%'
        ORDER BY COALESCE(al.release_date, '') DESC,
                 COALESCE(t.disc_number, 1) ASC,
                 COALESCE(t.track_number, 9999) ASC,
                 t.title_normalized ASC, t.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchByArtistName(normalizedArtist: String, limit: Int, offset: Int): List<TrackWithRelationsRow>

    @Query(
        """
        SELECT COUNT(*) FROM tracks t
        INNER JOIN artists ar ON ar.id = t.artist_id
        WHERE ar.name_normalized LIKE '%' || :normalizedArtist || '%'
        """
    )
    suspend fun countSearchByArtistName(normalizedArtist: String): Int

    @Query(
        """
        SELECT t.id, t.provider_track_id, t.musicbrainz_recording_id, t.album_id,
               al.title AS album_title, al.artwork_url AS album_artwork_url,
               t.artist_id, ar.name AS artist_name, t.title, t.duration_ms,
               t.track_number, t.disc_number, t.isrc, t.provider, t.last_synced_at
        FROM tracks t
        INNER JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.album_id = :albumId
        ORDER BY COALESCE(t.disc_number, 1) ASC,
                 COALESCE(t.track_number, 9999) ASC,
                 t.title_normalized ASC, t.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getTracklistByAlbumId(albumId: Long, limit: Int, offset: Int): List<TrackWithRelationsRow>

    @Query("SELECT COUNT(*) FROM tracks WHERE album_id = :albumId")
    suspend fun countByAlbumId(albumId: Long): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countAll(): Int
}

@Dao
interface CatalogSyncDao {
    @Query(
        """
        SELECT * FROM catalog_sync
        WHERE provider = :provider AND entity_type = :entityType AND query_normalized = :queryNormalized
        LIMIT 1
        """
    )
    suspend fun getByKey(provider: String, entityType: String, queryNormalized: String): CatalogSyncEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CatalogSyncEntity): Long

    @Update
    suspend fun update(entity: CatalogSyncEntity): Int

    @Query("SELECT * FROM catalog_sync ORDER BY last_synced_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<CatalogSyncEntity>
}

@Dao
interface CatalogCacheDao {
    @Query("SELECT * FROM catalog_cache WHERE cache_key = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): CatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CatalogCacheEntity)

    @Query("DELETE FROM catalog_cache WHERE expires_at <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int

    @Query("DELETE FROM catalog_cache WHERE cache_key = :cacheKey")
    suspend fun delete(cacheKey: String)
}
