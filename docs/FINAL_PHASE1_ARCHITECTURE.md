# FLACDL FINAL Phase 1 — Search Architecture, Backend Contract Proposal & Consumer Transition Plan

**Project:** FLACDL Android (`com.flacdl.directtester`)  
**Repository:** `https://github.com/rei-reika/FLACDL_App.git` (`master`)  
**Status:** Phase 1 Foundation Complete (OpenSubsonic Permanently Removed)

---

## 1. Architectural Separation of Concerns

FLACDL separates music discovery, metadata enrichment, candidate matching, source resolution, and download execution into five explicit boundaries:

| Stage | Responsibility | Android Package / Component |
|---|---|---|
| **A. Catalog Discovery** | Discover artists, albums, tracks, and provider identifiers via authorized catalog providers. | `com.example.catalog.provider` (`CatalogProvider`, `ItunesCatalogAdapter`), `com.example.catalog.search` (`CatalogDiscoveryGateway`) |
| **B. Metadata Enrichment** | Enrich catalog entries with ISRC, artwork, and normalized release details without duplicating search results. | `com.example.catalog.remote` (`MusicBrainzCatalogClient`, `ItunesCatalogClient`), `com.example.catalog.enrichment` |
| **C. Candidate Matching & Merging** | Normalize titles/artists, match by ISRC or title+artist+duration, merge duplicates deterministically, and keep internal match metadata out of consumer UI. | `com.example.catalog.matching` (`TrackMatcher`, `TrackTextNormalizer`), `com.example.catalog.search` (`CandidateDeduplicator`, `StructuredTrackCandidate`) |
| **D. Source Resolution** | Resolve a `CanonicalTrackIdentity` to an authorized downloadable resource (`DownloadResolutionResult`). Strictly decoupled from catalog discovery. | `com.example.catalog.resolver` (`DownloadSourceResolver`, `TrackMatchingDownloadSourceResolver`, `CachingDownloadSourceResolver`, `UnconfiguredDownloadSourceResolver`) |
| **E. Download Execution** | Manage download queues, progress, file validation, storage, and completion/error handling. *(Not implemented in Phase 1.)* | Future `download` / queue layer (existing `DirectDownloadManager` & `FlacFileValidator` retained for transition) |

> **OpenSubsonic Removal Policy:** OpenSubsonic has been permanently removed from the codebase (UI, config repository, Keystore vault, validator, client, resolver, DI wiring, and tests). Generic resolver and caching contracts (`DownloadSourceResolver`, `TrackMatchingDownloadSourceResolver`, `CachingDownloadSourceResolver`) are preserved and remain extensible for future authorized providers.

---

## 2. Responsibility Split: Android App vs. Future Remote Backend

| Capability | Android Application | Future Remote Backend (Proposed) |
|---|---|---|
| **UI, Navigation, Search UX** | Owns Compose UI, debounced search input, offline Room cache browsing, artist/album details, and local playback/library state. | None |
| **Catalog Discovery & Orchestration** | Consumes `CatalogDiscoveryGateway` (local Room + iTunes today; remote backend client in future). | Multi-provider catalog orchestration, provider credential management, rate-limit pooling, and cross-provider deduplication. |
| **Metadata Enrichment** | On-demand local enrichment (`MusicBrainzCatalogClient`) when operating offline/standalone. | Centralized ISRC & metadata enrichment cache shared across clients. |
| **Source Resolution** | Holds `DownloadSourceResolver` interface; never embeds unauthorized scraping or server credentials. | Authorized source resolution module (isolated from catalog search). |
| **Download Jobs** | Executes client-side download queue, progress UI, FLAC validation, and MediaStore/SAF storage. | Optional job coordination / handoff issuance (isolated from catalog search). |

---

## 3. Proposed Future Backend API Contract (Architectural Proposal)

> **Important:** This section defines a **proposed API contract** for a future remote search & catalog backend. It is **not** deployed in Phase 1, and no speculative networking code has been added to Android.

### 3.1 `GET /v1/search`
Unified structured search across tracks, albums, and artists.

**Query Parameters:**
- `q` (string, required): user search query.
- `type` (string, optional): `all` | `track` | `album` | `artist` (default `all`).
- `limit` (int, optional): `1..50` (default `20`).
- `offset` (int, optional): `>= 0` (default `0`).
- `country` (string, optional): ISO-3166-1 alpha-2 (default `ID`).

**Proposed Response (`200 OK`):**
```json
{
  "query": "Yoru ni Kakeru",
  "tracks": [
    {
      "canonical_key": "isrc:JPXS01900101",
      "title": "Yoru ni Kakeru",
      "artist": "YOASOBI",
      "album": "THE BOOK",
      "artwork_url": "https://...",
      "duration_ms": 261000,
      "release_year": 2021,
      "track_number": 3,
      "isrc": "JPXS01900101",
      "provider_ids": {
        "apple_itunes": "1536243223"
      }
    }
  ],
  "albums": [],
  "artists": [],
  "pagination": {
    "limit": 20,
    "offset": 0,
    "total_count": 1,
    "has_more": false
  }
}
```

### 3.2 `GET /v1/catalog/artists/{artistId}` & `GET /v1/catalog/albums/{albumId}`
- Returns normalized artist discography or ordered album tracklist matching `StructuredTrackCandidate` fields.
- Keeps provider-specific raw payloads internal to the backend.

### 3.3 `POST /v1/resolve` *(Separate Source Resolution Module)*
- **Input:** `CanonicalTrackIdentity` (`catalog_provider_id`, `catalog_track_id`, `title`, `artist`, `album`, `duration_ms`, `isrc`).
- **Output:** Typed resolution status (`resolved`, `ambiguous`, `unverified`, `unavailable`, `error`) with short-TTL availability expiry, matching `DownloadResolutionResult`.

### 3.4 `POST /v1/jobs` *(Separate Download Execution Module)*
- Accepts a resolved source identity (never raw catalog IDs directly) to create and track download jobs independently of catalog discovery.

---

## 4. Consumer Application Transition Plan

Audit of existing screens in `com.example.ui`:

| Current Screen | Current Role | Transition Decision | Target Consumer Role |
|---|---|---|---|
| `CatalogSearchScreen` | Offline-first Room + iTunes search | **Retain & Elevate** | Primary consumer **Search** tab (remove sync jargon; present clean consumer search & results). |
| `ArtistDetailScreen` | Artist metadata & discography | **Retain & Polish** | Consumer **Artist** detail screen. |
| `AlbumDetailScreen` | Album metadata & tracklist | **Retain & Polish** | Consumer **Album** detail screen with track selection (OpenSubsonic panel removed in Phase 1). |
| `DirectDownloadScreen` | Direct FLAC download + SAF export | **Evolve** | Consumer **Downloads / Queue** screen (remove diagnostic report controls from consumer UI). |
| `DashboardScreen` | VPS health, latency telemetry, pairing | **Evolve / Split** | Move account/pairing into **Settings**; retire latency tester & mode sheet from consumer navigation. |
| `SearchScreen` (Tester) | Legacy VPS POC track search | **Retire after migration** | Replaced by unified consumer **Search** backed by `CatalogDiscoveryGateway`. |
| `HandoffScreen` | Developer Lucida handoff inspector | **Retire** | Internal implementation detail; should not be a user-facing tab. |
| `WorkerMonitorScreen` | Developer worker polling telemetry | **Retire** | Replaced by clean progress states in **Downloads**. |
| `OpenSubsonicSettingsScreen` | OpenSubsonic server management | **Removed (Phase 1)** | Permanently deleted. |

### Target Consumer Navigation (Subsequent Phases)
1. **Search** — Unified music discovery (`CatalogDiscoveryGateway`).
2. **Downloads** — Active download queue, progress, and download history.
3. **Library** — Downloaded local music library.
4. **Settings** — Clean consumer preferences (audio format, storage folder, account) with zero developer jargon.
