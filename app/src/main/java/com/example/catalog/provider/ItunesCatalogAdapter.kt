package com.example.catalog.provider

import com.example.catalog.model.CatalogProviders
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteAlbumLookupResult
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.remote.OkHttpItunesCatalogClient

/**
 * Adapts [ItunesCatalogProvider] (raw Apple iTunes JSON client) to the provider-agnostic
 * [CatalogProvider] contract, encapsulating all iTunes JSON parsing inside this adapter.
 */
class ItunesCatalogAdapter(
    private val client: ItunesCatalogProvider
) : CatalogProvider {

    override val descriptor: ProviderDescriptor = DEFAULT_DESCRIPTOR

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        country: String
    ): CatalogProviderResult<List<RemoteArtistDto>> {
        val safeLimit = limit.coerceIn(1, capabilities.maxSearchLimit)
        val rawJson = client.fetchSearchArtistsJson(query, safeLimit, country)
        val parsed = OkHttpItunesCatalogClient.parseArtists(rawJson)
        return CatalogProviderResult.Success(data = parsed, rawPayloadForCache = rawJson)
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        country: String
    ): CatalogProviderResult<List<RemoteAlbumDto>> {
        val safeLimit = limit.coerceIn(1, capabilities.maxSearchLimit)
        val rawJson = client.fetchSearchAlbumsJson(query, safeLimit, country)
        val parsed = OkHttpItunesCatalogClient.parseAlbums(rawJson)
        return CatalogProviderResult.Success(data = parsed, rawPayloadForCache = rawJson)
    }

    override suspend fun searchTracks(
        query: String,
        limit: Int,
        country: String
    ): CatalogProviderResult<List<RemoteTrackDto>> {
        val safeLimit = limit.coerceIn(1, capabilities.maxSearchLimit)
        val rawJson = client.fetchSearchTracksJson(query, safeLimit, country)
        val parsed = OkHttpItunesCatalogClient.parseTracks(rawJson)
        return CatalogProviderResult.Success(data = parsed, rawPayloadForCache = rawJson)
    }

    override suspend fun getArtistAlbums(
        providerArtistId: String,
        limit: Int,
        country: String
    ): CatalogProviderResult<List<RemoteAlbumDto>> {
        val safeLimit = limit.coerceIn(1, capabilities.maxSearchLimit)
        val rawJson = client.fetchArtistAlbumsJson(providerArtistId, safeLimit, country)
        val parsed = OkHttpItunesCatalogClient.parseAlbums(rawJson)
        return CatalogProviderResult.Success(data = parsed, rawPayloadForCache = rawJson)
    }

    override suspend fun getAlbumDetail(
        providerAlbumId: String,
        country: String
    ): CatalogProviderResult<RemoteAlbumDto?> {
        val rawJson = client.fetchAlbumTracklistJson(providerAlbumId, country)
        val parsed = OkHttpItunesCatalogClient.parseAlbumLookup(rawJson)
        return CatalogProviderResult.Success(data = parsed.album, rawPayloadForCache = rawJson)
    }

    override suspend fun getAlbumTracklist(
        providerAlbumId: String,
        country: String
    ): CatalogProviderResult<RemoteAlbumLookupResult> {
        val rawJson = client.fetchAlbumTracklistJson(providerAlbumId, country)
        val parsed = OkHttpItunesCatalogClient.parseAlbumLookup(rawJson)
        return CatalogProviderResult.Success(data = parsed, rawPayloadForCache = rawJson)
    }

    companion object {
        val DEFAULT_CAPABILITIES = ProviderCapabilities(
            supportsArtistSearch = true,
            supportsAlbumSearch = true,
            supportsTrackSearch = true,
            supportsArtistDetail = false,
            supportsArtistAlbums = true,
            supportsAlbumDetail = true,
            supportsAlbumTracklist = true,
            supportsIsrc = false,
            supportsRemotePagination = false,
            maxSearchLimit = ItunesCatalogProvider.MAX_SEARCH_LIMIT,
            defaultCountry = ItunesCatalogProvider.DEFAULT_COUNTRY
        )

        val DEFAULT_DESCRIPTOR = ProviderDescriptor(
            id = CatalogProviders.ITUNES,
            displayName = "Apple iTunes Catalog",
            isPrimaryDiscovery = true,
            capabilities = DEFAULT_CAPABILITIES
        )
    }
}
