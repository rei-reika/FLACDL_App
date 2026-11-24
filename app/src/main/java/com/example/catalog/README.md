# FLACDL Catalog & Search Foundation

Independent offline-first music catalog and search architecture inside FLACDL Android (`com.flacdl.directtester`, package `com.example.catalog`).

## Architecture Boundaries

1. **Catalog Discovery (`provider/`, `search/`):**
   - `CatalogProvider` abstraction (`ItunesCatalogAdapter` backed by Apple iTunes Search & Lookup API, throttled via `CatalogRateLimiter.forItunes()`).
   - `CatalogDiscoveryGateway` & `StructuredTrackCandidate` provide a provider-agnostic boundary adaptable to either local Room+iTunes discovery or a future remote search backend.
2. **Metadata Enrichment (`remote/`, `enrichment/`):**
   - `MusicBrainzCatalogClient` (strict 1 req/sec throttling + exponential backoff on HTTP 503) enriches tracks with ISRC on-demand without duplicating search results.
3. **Candidate Matching & Merging (`matching/`, `search/`):**
   - `TrackMatcher` & `TrackTextNormalizer`: deterministic ISRC, normalized title/artist, album, and duration matching with confidence/evidence and ambiguity detection.
   - `CandidateDeduplicator`: deterministic duplicate merging across providers while keeping internal match metadata separate from consumer UI fields.
4. **Local Storage (`db/`, `repository/`):**
   - Room (`LocalCatalogDatabase`, `flacdl_local_catalog.db`) with normalized `artists`, `albums`, `tracks`, `catalog_sync`, and `catalog_cache` tables and FK-preserving upserts.
5. **Source Resolution (`resolver/`):**
   - Strictly separated from catalog discovery via `DownloadSourceResolver`, `TrackMatchingDownloadSourceResolver`, `CachingDownloadSourceResolver`, and `UnconfiguredDownloadSourceResolver`.
   - OpenSubsonic has been permanently removed from the architecture.

See `docs/FINAL_PHASE1_ARCHITECTURE.md` for the complete Phase 1 architecture, proposed future backend API contract, and consumer transition plan.
