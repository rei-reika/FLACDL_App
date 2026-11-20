package com.example

import com.example.catalog.matching.TrackMatchMethod
import com.example.catalog.resolver.CachingDownloadSourceResolver
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.ResolverCachePolicy
import com.example.catalog.resolver.ResolverErrorType
import com.example.catalog.resolver.ResolverRetryPolicy
import com.example.catalog.resolver.TrackMatchingDownloadSourceResolver
import com.example.catalog.resolver.opensubsonic.OkHttpOpenSubsonicClient
import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuth
import com.example.catalog.resolver.opensubsonic.OpenSubsonicClient
import com.example.catalog.resolver.opensubsonic.OpenSubsonicDownloadCandidateProvider
import com.example.catalog.resolver.opensubsonic.OpenSubsonicServerConfig
import com.example.catalog.resolver.opensubsonic.OpenSubsonicSongDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenSubsonicResolverIntegrationTest {

    private val serverConfig = OpenSubsonicServerConfig(
        serverBaseUrl = "https://music.example.local",
        auth = OpenSubsonicAuth.TokenAuth(
            username = "demo-user",
            token = "secret-token-value",
            salt = "secret-salt-value"
        ),
        timeoutMs = 5_000L,
        maxCandidates = 10
    )

    private val appleTrack = CanonicalTrackIdentity(
        localTrackId = 7L,
        catalogProviderId = "apple_itunes",
        catalogTrackId = "apple-999888777",
        title = "Yoru ni Kakeru",
        artist = "YOASOBI",
        album = "THE BOOK",
        durationMs = 261_000L,
        trackNumber = 3,
        releaseYear = 2021,
        isrc = "JPXS01900101"
    )

    // =========================================================================
    // 1. OpenSubsonic Parser & Adapter Tests
    // =========================================================================

    @Test
    fun `valid search3 response maps song ID ISRC MusicBrainz ID duration and FLAC hints`() = runTest {
        val rawJson = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "openSubsonic": true,
                "searchResult3": {
                  "song": [
                    {
                      "id": "nav-song-42",
                      "title": "Yoru ni Kakeru",
                      "artist": "YOASOBI",
                      "album": "THE BOOK",
                      "duration": 261,
                      "track": 3,
                      "year": 2021,
                      "suffix": "flac",
                      "contentType": "audio/flac",
                      "bitDepth": 24,
                      "samplingRate": 96000,
                      "musicBrainzId": "189002e7-3285-4e2e-92a3-7f6c30d407a2",
                      "isrc": ["jp-xs0-19-00101"]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val songs = OkHttpOpenSubsonicClient.parseSearch3Response(rawJson)
        assertEquals(1, songs.size)

        val provider = OpenSubsonicDownloadCandidateProvider(
            config = serverConfig,
            client = FakeOpenSubsonicClient(songs = songs)
        )
        val candidates = provider.fetchCandidates(appleTrack)
        assertEquals(1, candidates.size)

        val cand = candidates.first()
        assertEquals("nav-song-42", cand.sourceTrackId)
        assertNotEquals(appleTrack.catalogTrackId, cand.sourceTrackId)
        assertEquals("JPXS01900101", cand.metadata.isrc)
        assertEquals("189002e7-3285-4e2e-92a3-7f6c30d407a2", cand.musicBrainzRecordingId)
        assertEquals(261_000L, cand.metadata.durationMs)
        assertTrue(cand.qualityHints.contains("FLAC"))
        assertTrue(cand.qualityHints.contains("LOSSLESS"))
        assertTrue(cand.qualityHints.contains("24bit/96kHz"))
        assertFalse(cand.sourceReference.contains("secret-token-value"))
        assertFalse(serverConfig.toString().contains("secret-token-value"))
    }

    @Test
    fun `empty search3 response returns empty candidates cleanly`() = runTest {
        val rawJson = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {}
              }
            }
        """.trimIndent()

        val songs = OkHttpOpenSubsonicClient.parseSearch3Response(rawJson)
        assertTrue(songs.isEmpty())
    }

    @Test
    fun `invalid JSON and missing subsonic-response throw classified lookup exception`() {
        try {
            OkHttpOpenSubsonicClient.parseSearch3Response("{not-valid-json")
            fail("Expected DownloadSourceLookupException for malformed JSON")
        } catch (e: DownloadSourceLookupException) {
            assertEquals(ResolverErrorType.UNKNOWN, e.errorType)
        }

        try {
            OkHttpOpenSubsonicClient.parseSearch3Response("""{"unexpected": true}""")
            fail("Expected DownloadSourceLookupException for missing envelope")
        } catch (e: DownloadSourceLookupException) {
            assertEquals(ResolverErrorType.UNKNOWN, e.errorType)
        }
    }

    @Test
    fun `subsonic authentication error code maps to non-retryable AUTHENTICATION error`() = runTest {
        val authErrorJson = """
            {
              "subsonic-response": {
                "status": "failed",
                "version": "1.16.1",
                "error": {
                  "code": 40,
                  "message": "Wrong username or password."
                }
              }
            }
        """.trimIndent()

        try {
            OkHttpOpenSubsonicClient.parseSearch3Response(authErrorJson)
            fail("Expected AUTHENTICATION exception")
        } catch (e: DownloadSourceLookupException) {
            assertEquals(ResolverErrorType.AUTHENTICATION, e.errorType)
            assertFalse(e.errorType.isRetryable)
        }
    }

    @Test
    fun `malformed song entries missing id or title are skipped safely`() {
        val rawJson = """
            {
              "subsonic-response": {
                "status": "ok",
                "searchResult3": {
                  "song": [
                    {"id": "", "title": "No ID", "artist": "YOASOBI"},
                    {"id": "valid-1", "title": "", "artist": "YOASOBI"},
                    {"id": "valid-2", "title": "Idol", "artist": "YOASOBI", "duration": -5}
                  ]
                }
              }
            }
        """.trimIndent()

        val songs = OkHttpOpenSubsonicClient.parseSearch3Response(rawJson)
        assertEquals(1, songs.size)
        assertEquals("valid-2", songs.first().id)
        assertEquals(null, songs.first().durationSeconds)
    }

    @Test
    fun `timeout network and auth failures propagate through resolver with retry classification`() = runTest {
        // Timeout -> retryable
        val timeoutClient = FakeOpenSubsonicClient(
            throwError = DownloadSourceLookupException(ResolverErrorType.TIMEOUT, "Timed out")
        )
        val timeoutResolver = buildPipeline(timeoutClient, maxRetries = 1)
        val timeoutRes = timeoutResolver.resolve(appleTrack) as DownloadResolutionResult.Error
        assertEquals(ResolverErrorType.TIMEOUT, timeoutRes.errorType)
        assertTrue(timeoutRes.retryable)
        assertEquals(2, timeoutClient.searchCalls)

        // Network -> retryable
        val netClient = FakeOpenSubsonicClient(
            throwError = DownloadSourceLookupException(ResolverErrorType.NETWORK, "Connection refused")
        )
        val netResolver = buildPipeline(netClient, maxRetries = 1)
        val netRes = netResolver.resolve(appleTrack) as DownloadResolutionResult.Error
        assertEquals(ResolverErrorType.NETWORK, netRes.errorType)
        assertTrue(netRes.retryable)
        assertEquals(2, netClient.searchCalls)

        // Auth -> non-retryable (1 attempt only)
        val authClient = FakeOpenSubsonicClient(
            throwError = DownloadSourceLookupException(ResolverErrorType.AUTHENTICATION, "Invalid API key")
        )
        val authResolver = buildPipeline(authClient, maxRetries = 2)
        val authRes = authResolver.resolve(appleTrack) as DownloadResolutionResult.Error
        assertEquals(ResolverErrorType.AUTHENTICATION, authRes.errorType)
        assertFalse(authRes.retryable)
        assertEquals(1, authClient.searchCalls)
    }

    // =========================================================================
    // 2. Full Matching & Caching Integration Tests
    // =========================================================================

    @Test
    fun `full pipeline resolves verified ISRC match and caches subsequent lookup`() = runTest {
        val client = FakeOpenSubsonicClient(
            songs = listOf(
                OpenSubsonicSongDto(
                    id = "nav-isrc-1",
                    title = "Yoru ni Kakeru",
                    artist = "YOASOBI",
                    album = "THE BOOK",
                    durationSeconds = 261,
                    isrcs = listOf("JPXS01900101"),
                    suffix = "flac"
                )
            )
        )
        val resolver = buildPipeline(client)

        val first = resolver.resolve(appleTrack)
        val second = resolver.resolve(appleTrack)

        assertTrue(first is DownloadResolutionResult.Resolved)
        val resolved = first as DownloadResolutionResult.Resolved
        assertEquals("nav-isrc-1", resolved.identity.sourceTrackId)
        assertEquals(TrackMatchMethod.ISRC_EXACT, resolved.evidence.matchMethod)
        assertEquals(first, second)
        assertEquals(1, client.searchCalls)
    }

    @Test
    fun `full pipeline resolves verified metadata match without ISRC`() = runTest {
        val trackNoIsrc = appleTrack.copy(isrc = null)
        val client = FakeOpenSubsonicClient(
            songs = listOf(
                OpenSubsonicSongDto(
                    id = "nav-meta-1",
                    title = "Yoru ni Kakeru",
                    artist = "YOASOBI",
                    album = "THE BOOK",
                    durationSeconds = 262, // 1,000 ms delta <= 3,000 ms
                    suffix = "flac"
                )
            )
        )
        val resolver = buildPipeline(client)

        val result = resolver.resolve(trackNoIsrc)
        assertTrue(result is DownloadResolutionResult.Resolved)
        val resolved = result as DownloadResolutionResult.Resolved
        assertEquals("nav-meta-1", resolved.identity.sourceTrackId)
        assertEquals(TrackMatchMethod.TITLE_ARTIST_DURATION, resolved.evidence.matchMethod)
    }

    @Test
    fun `full pipeline returns Ambiguous with deterministic ordering for competing songs`() = runTest {
        val trackNoIsrc = appleTrack.copy(isrc = null, album = null)
        val songA = OpenSubsonicSongDto(id = "nav-alpha", title = "Yoru ni Kakeru", artist = "YOASOBI", durationSeconds = 261)
        val songB = OpenSubsonicSongDto(id = "nav-beta", title = "Yoru ni Kakeru", artist = "YOASOBI", durationSeconds = 261)

        val forward = buildPipeline(FakeOpenSubsonicClient(listOf(songA, songB)))
            .resolve(trackNoIsrc) as DownloadResolutionResult.Ambiguous
        val reversed = buildPipeline(FakeOpenSubsonicClient(listOf(songB, songA)))
            .resolve(trackNoIsrc) as DownloadResolutionResult.Ambiguous

        assertEquals(2, forward.candidates.size)
        assertEquals(listOf("nav-alpha", "nav-beta"), forward.candidates.map { it.candidate.sourceTrackId })
        assertEquals(
            forward.candidates.map { it.candidate.sourceTrackId },
            reversed.candidates.map { it.candidate.sourceTrackId }
        )
    }

    @Test
    fun `full pipeline rejects live versus studio variant conflict`() = runTest {
        val trackNoIsrc = appleTrack.copy(isrc = null)
        val client = FakeOpenSubsonicClient(
            songs = listOf(
                OpenSubsonicSongDto(
                    id = "nav-live-1",
                    title = "Yoru ni Kakeru (Live at Budokan)",
                    artist = "YOASOBI",
                    durationSeconds = 262
                )
            )
        )

        val result = buildPipeline(client).resolve(trackNoIsrc)
        assertTrue(result is DownloadResolutionResult.Unavailable)
        assertTrue((result as DownloadResolutionResult.Unavailable).reason.contains("version conflict", ignoreCase = true))
    }

    @Test
    fun `full pipeline returns Unavailable and never fabricates source ID when library has no match`() = runTest {
        val client = FakeOpenSubsonicClient(songs = emptyList())
        val result = buildPipeline(client).resolve(appleTrack)

        assertTrue(result is DownloadResolutionResult.Unavailable)
        assertEquals(serverConfig.providerId, (result as DownloadResolutionResult.Unavailable).providerId)
    }

    @Test
    fun `full pipeline returns Unverified when duration is missing and never promotes to Resolved`() = runTest {
        val trackNoDuration = appleTrack.copy(isrc = null, durationMs = null)
        val client = FakeOpenSubsonicClient(
            songs = listOf(
                OpenSubsonicSongDto(
                    id = "nav-nodur-1",
                    title = "Yoru ni Kakeru",
                    artist = "YOASOBI",
                    durationSeconds = 261
                )
            )
        )

        val result = buildPipeline(client).resolve(trackNoDuration)
        assertTrue(result is DownloadResolutionResult.Unverified)
        assertEquals("nav-nodur-1", (result as DownloadResolutionResult.Unverified).candidate.sourceTrackId)
    }

    @Test
    fun `plaintext HTTP is rejected for external hosts and allowed only for local LAN hosts`() {
        val externalHttpConfig = serverConfig.copy(serverBaseUrl = "http://music.public-domain.example")
        try {
            OkHttpOpenSubsonicClient.buildSearch3Url(externalHttpConfig, "YOASOBI", 5)
            fail("Expected INVALID_REQUEST for plaintext external HTTP endpoint")
        } catch (e: DownloadSourceLookupException) {
            assertEquals(ResolverErrorType.INVALID_REQUEST, e.errorType)
            assertFalse(e.message.orEmpty().contains("secret-token-value"))
        }

        val localLanConfig = serverConfig.copy(serverBaseUrl = "http://192.168.1.50:4533")
        val lanUrl = OkHttpOpenSubsonicClient.buildSearch3Url(localLanConfig, "YOASOBI", 5)
        assertEquals("http", lanUrl.scheme)
        assertEquals("192.168.1.50", lanUrl.host)

        val httpsExternalConfig = serverConfig.copy(serverBaseUrl = "https://music.public-domain.example")
        val httpsUrl = OkHttpOpenSubsonicClient.buildSearch3Url(httpsExternalConfig, "YOASOBI", 5)
        assertEquals("https", httpsUrl.scheme)
    }

    @Test
    fun `HTTP redirects are disabled and rejected to prevent credential leakage`() = runTest {
        val redirectClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://evil.example/steal")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val client = OkHttpOpenSubsonicClient(config = serverConfig, baseClient = redirectClient)
        try {
            client.searchSongs("YOASOBI", 5)
            fail("Expected INVALID_REQUEST on HTTP 302 redirect")
        } catch (e: DownloadSourceLookupException) {
            assertEquals(ResolverErrorType.INVALID_REQUEST, e.errorType)
            assertTrue(e.message.orEmpty().contains("redirect", ignoreCase = true))
            assertFalse(e.message.orEmpty().contains("secret-token-value"))
        }
    }

    private fun buildPipeline(
        client: OpenSubsonicClient,
        maxRetries: Int = 1
    ): CachingDownloadSourceResolver {
        val provider = OpenSubsonicDownloadCandidateProvider(
            config = serverConfig,
            client = client
        )
        val matchingResolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = provider,
            retryPolicy = ResolverRetryPolicy(maxRetries = maxRetries, baseBackoffMs = 1L),
            delayFn = {}
        )
        return CachingDownloadSourceResolver(
            delegate = matchingResolver,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L)
        )
    }

    private class FakeOpenSubsonicClient(
        private val songs: List<OpenSubsonicSongDto> = emptyList(),
        private val throwError: Exception? = null
    ) : OpenSubsonicClient {
        var searchCalls: Int = 0

        override suspend fun searchSongs(query: String, songCount: Int): List<OpenSubsonicSongDto> {
            searchCalls++
            throwError?.let { throw it }
            return songs
        }
    }
}
