package com.example.catalog.provider

import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteAlbumLookupResult
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto

/**
 * Explicit capability declaration for a read-only catalog discovery provider.
 *
 * Allows callers and repositories to inspect what a provider supports without
 * hardcoding provider IDs or catching unexpected exceptions for unsupported endpoints.
 */
data class ProviderCapabilities(
    val supportsArtistSearch: Boolean = false,
    val supportsAlbumSearch: Boolean = false,
    val supportsTrackSearch: Boolean = false,
    val supportsArtistDetail: Boolean = false,
    val supportsArtistAlbums: Boolean = false,
    val supportsAlbumDetail: Boolean = false,
    val supportsAlbumTracklist: Boolean = false,
    val supportsIsrc: Boolean = false,
    val supportsRemotePagination: Boolean = false,
    val maxSearchLimit: Int = 50,
    val defaultCountry: String = "ID"
)

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val isPrimaryDiscovery: Boolean = false,
    val capabilities: ProviderCapabilities
)

/**
 * Strongly typed result for catalog provider operations.
 * Keeps provider-specific JSON parsing inside adapters while allowing optional
 * raw payload pass-through for Room cache storage.
 */
sealed class CatalogProviderResult<out T> {
    data class Success<T>(
        val data: T,
        val rawPayloadForCache: String? = null
    ) : CatalogProviderResult<T>()

    data class Unsupported(
        val providerId: String,
        val operation: String
    ) : CatalogProviderResult<Nothing>()
}

/**
 * Provider-agnostic catalog discovery contract.
 *
 * All operations default to [CatalogProviderResult.Unsupported] so providers only
 * implement capabilities they legitimately support.
 */
interface CatalogProvider {
    val descriptor: ProviderDescriptor

    val providerId: String
        get() = descriptor.id

    val capabilities: ProviderCapabilities
        get() = descriptor.capabilities

    suspend fun searchArtists(
        query: String,
        limit: Int = 25,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<List<RemoteArtistDto>> =
        CatalogProviderResult.Unsupported(providerId, OP_SEARCH_ARTISTS)

    suspend fun searchAlbums(
        query: String,
        limit: Int = 50,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<List<RemoteAlbumDto>> =
        CatalogProviderResult.Unsupported(providerId, OP_SEARCH_ALBUMS)

    suspend fun searchTracks(
        query: String,
        limit: Int = 50,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<List<RemoteTrackDto>> =
        CatalogProviderResult.Unsupported(providerId, OP_SEARCH_TRACKS)

    suspend fun getArtistDetail(
        providerArtistId: String,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<RemoteArtistDto?> =
        CatalogProviderResult.Unsupported(providerId, OP_ARTIST_DETAIL)

    suspend fun getArtistAlbums(
        providerArtistId: String,
        limit: Int = 50,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<List<RemoteAlbumDto>> =
        CatalogProviderResult.Unsupported(providerId, OP_ARTIST_ALBUMS)

    suspend fun getAlbumDetail(
        providerAlbumId: String,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<RemoteAlbumDto?> =
        CatalogProviderResult.Unsupported(providerId, OP_ALBUM_DETAIL)

    suspend fun getAlbumTracklist(
        providerAlbumId: String,
        country: String = capabilities.defaultCountry
    ): CatalogProviderResult<RemoteAlbumLookupResult> =
        CatalogProviderResult.Unsupported(providerId, OP_ALBUM_TRACKLIST)

    companion object {
        const val OP_SEARCH_ARTISTS = "searchArtists"
        const val OP_SEARCH_ALBUMS = "searchAlbums"
        const val OP_SEARCH_TRACKS = "searchTracks"
        const val OP_ARTIST_DETAIL = "getArtistDetail"
        const val OP_ARTIST_ALBUMS = "getArtistAlbums"
        const val OP_ALBUM_DETAIL = "getAlbumDetail"
        const val OP_ALBUM_TRACKLIST = "getAlbumTracklist"
    }
}
