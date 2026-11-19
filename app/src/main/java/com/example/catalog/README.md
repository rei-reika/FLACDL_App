# FLACDL Local Catalog Engine (Phase 2A)

Independent offline-first music catalog engine inside `FLACDL Direct Tester` (`com.flacdl.directtester`, package `com.example.catalog`).

## Architecture

- **Primary Discovery Provider:** Apple iTunes Search & Lookup API (`https://itunes.apple.com/search`, `/lookup`, `country=ID`, max `limit=200`, throttled to ~18 req/min via `CatalogRateLimiter.forItunes()`).
- **Secondary Enrichment Provider:** MusicBrainz API (`https://musicbrainz.org/ws/2/`, descriptive `User-Agent`, strict 1 req/sec throttling + exponential backoff on HTTP 503). Used strictly on-demand via explicit enrichment methods — never on keystrokes and never blocking local search.
- **Local Storage:** Room (`LocalCatalogDatabase`, `flacdl_local_catalog.db`, version `1`, explicit migration registry without destructive fallback).
  - Normalized tables: `artists`, `albums`, `tracks`, `catalog_sync`, `catalog_cache`.
  - Local auto-generated primary keys (`id`) are strictly separated from provider IDs (`provider`, `provider_*_id` unique composite indexes).
  - Safe FK-preserving upserts (`findByProviderId` + `update` / `insert` inside `withTransaction`) prevent destructive `REPLACE` cascades.
- **Offline Local Search:** Case-insensitive, whitespace-normalized SQL queries with deterministic ordering and `LIMIT`/`OFFSET` pagination (`CatalogPage<T>`).

## Explicit Boundaries

- Zero dependency on LucidaDL Search or production VPS backend (`/root/t-bot/`).
- Never fabricates or infers Amazon Music URLs from Apple or MusicBrainz IDs.
- No fake playlists (playlist entities are out of scope until a verified playlist provider exists).
- Artwork URLs are stored as metadata only and subject to Apple promotional usage guidelines.

## Running Engine Tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.LocalCatalogEngineTest" --tests "com.example.FlacdlTesterUnitTest"
```
