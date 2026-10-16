# OpenMaps Production Platform v1

OpenMaps is being treated as a shipping navigation product, not a prototype.

## Product standard

OpenMaps must be credible as a worldwide navigation product and capable of competing on reliability, search coverage, routing quality, live navigation, privacy, and differentiated AR guidance.

## Production workstreams

1. Global Search and Address Intelligence
   - Worldwide geocoding with no country hard-lock.
   - Current-location bias for ambiguous/local searches.
   - Composite geocoding with production SLA provider(s) plus OSM fallback.
   - Reverse geocoding.
   - Address/POI deduplication, confidence and proximity ranking.
   - Route-distance ranking for selected/top candidates.

2. Production Routing and Traffic
   - Replace public/demo Valhalla endpoint.
   - Traffic-aware ETA, alternate routes, rerouting, closures and incidents.
   - Map matching / snap-to-road for noisy GPS.
   - Lane/exit intelligence where provider data exists.

3. Global Places
   - Replace public Overpass as primary production source.
   - Restaurants, gas, parking, EV, hospitals, hotels, schools and businesses.
   - Entrances/parking/access metadata where licensed and available.

4. Live Trip Sharing
   - Expiring opaque token.
   - Realtime location + ETA updates.
   - Viewer requires no account.
   - Stop sharing, auto-expiry, arrival cleanup, rate limiting and privacy controls.

5. Community Road Reports
   - Police, traffic, crash, lane closed, construction, road hazard, flooding,
     disabled vehicle and object on road.
   - Confirmation/dismissal, clustering, TTL, confidence, moderation and abuse controls.

6. Human Navigation Voice
   - Neural/cloud voice as primary.
   - Android TTS as offline/failure fallback.
   - Low latency, SSML/pronunciation controls, street-name handling and phrase caching.

7. Internationalization
   - App language separate from navigation voice.
   - Locale-aware units/time/number formatting.
   - RTL support.
   - Region-specific navigation terminology and street pronunciation.

8. Backend Service Layer
   - OpenMaps API gateway/service layer between clients and external providers.
   - Provider key protection, caching, failover, rate limiting, observability and cost controls.

9. Reliability and Observability
   - Crash/error reporting.
   - Search zero-result rate.
   - Search/routing/provider latency.
   - Route success and reroute rates.
   - Trip share health.
   - AR readiness/anchor/render health.
   - Provider failover telemetry.

10. Privacy and Security
    - Minimal location retention.
    - TLS everywhere.
    - Short-lived trip-share tokens.
    - User-controlled sharing.
    - Server-side secrets.
    - Retention/deletion policy and regional privacy requirements.

11. Offline/Degraded Operation
    - Cached route/map corridor.
    - Last-known navigation state.
    - Offline/fallback voice.
    - Clear degraded-service behavior.

12. Regional Capability Matrix
    - Search, routing, traffic, lane data, speed limits, parking and AR coverage are tracked by region.
    - Map Navigation remains broadly available while Live AR degrades honestly where Geospatial/VPS support is limited.

## Phase 1: Worldwide search foundation

The first production slice removes the US-only geocoder restriction and makes search location-aware.

Implemented on `production/platform-v1`:
- `GeocodingSearchContext` provider-neutral ranking context.
- Worldwide Nominatim fallback (no `countrycodes=us`).
- Unbounded proximity bias from current GPS location.
- SearchViewModel receives the latest GPS fix.
- Higher result limit for better candidate coverage.

This is a foundation, not the final production geocoder. Public Nominatim remains unsuitable as the sole production service; the next step is a composite provider implementation backed by an SLA-capable global geocoder.

## Definition of production-ready

A feature is not production-ready because the UI exists. It is production-ready only when:
- backend/provider is real and monitored;
- failure/fallback behavior is explicit;
- no fake data or fake success states exist;
- security/privacy controls are implemented;
- automated tests pass;
- Android physical-device validation passes;
- provider and geographic limitations are documented;
- operational telemetry exists.


## Phase 2: Global search intelligence

Implemented on `production/platform-v1`:

- Production geocoding gateway v3 with multi-provider aggregation.
- HERE support remains credential-gated through server-side `HERE_API_KEY`; no provider secret is shipped in the Android app.
- Nominatim remains a worldwide fallback.
- HERE and Nominatim results are normalized into one provider-neutral schema.
- Cross-provider duplicate merging is performed before results are returned.
- Search ranking now combines text relevance, address completeness, provider confidence and proximity.
- Exact-address queries are detected and ranked differently so a distant exact address is not incorrectly buried by nearby but irrelevant places.
- POI/business/school queries use HERE Discover when configured and worldwide Nominatim otherwise.
- Reverse geocoding is deployed as a separate production Edge Function with HERE-first / Nominatim-fallback behavior.
- Android now reverse-geocodes live GPS fixes after meaningful movement and stores the resolved human-readable place in `MapUiState.currentLocationPlace`.
- Reverse geocoding is throttled by movement rather than run on every one-second GPS update.

Still required before claiming full commercial search production readiness:

- Configure an SLA-backed commercial provider credential in the backend.
- Run physical-device/global-region acceptance tests and measure zero-result/latency/failover rates.
- Add backend observability and shared/global rate limiting for launch traffic.


## Phase 3: Production routing and traffic foundation

Implemented on `production/platform-v1`:

- Deployed `route` Edge Function v2 as the OpenMaps routing gateway.
- Added server-side HERE Routing API v8 integration, activated only when `HERE_API_KEY` is configured.
- HERE car routing is requested with time/traffic-aware routing enabled, plus route geometry, summaries and maneuver instructions.
- Route responses now preserve live traffic-aware duration, free-flow/base duration, typical historical duration and computed non-negative traffic delay when the provider supplies them.
- Added flexible-polyline decoding in the backend so provider-specific HERE geometry never leaks into Android.
- Added `BackendRoutingRepository` for provider-neutral route responses.
- Added `CompositeRoutingRepository`: production backend first, existing Valhalla route engine as an explicit fallback.
- Extended `RouteResult` with optional traffic metadata while preserving compatibility with all existing navigation/AR code.
- Existing route preview and navigation ETA automatically consume the provider's traffic-aware `durationSeconds` when commercial routing is active.
- Added routing response parser and failover tests.
- Provider keys stay server-side; the APK contains only the OpenMaps gateway URL and Supabase publishable key.

Still required before full production routing readiness:

- Configure the commercial HERE credential in the backend.
- Add route alternatives to the provider-neutral domain model and Route Preview UI.
- Add production map matching / snap-to-road for noisy GPS traces.
- Add traffic incidents/closures feed integration and connect it to Road Intelligence.
- Add lane/exit guidance from a provider that supplies trustworthy lane data.
- Add routing latency/failure/fallback observability and launch-scale rate controls.
- Run worldwide route acceptance testing across representative countries and road classes.


## Phase 2B: Global search production hardening

Implemented on `production/platform-v1` and the OpenMaps Supabase project:

- Geocoding Edge Function upgraded to v4.
- Added launch-facing search telemetry without storing raw search text; only a SHA-256 query hash is retained.
- Telemetry captures result count, provider set, query type, latency, whether GPS bias was present, locale, zero-result/error outcome, fallback use and cache hits.
- Added a 24-hour health view for request volume, success rate, zero-result rate, error count, average latency, p95 latency, fallback count and cache hits.
- Added database-backed per-client rate limiting at 60 requests/minute using a hashed client address; raw IP addresses are not stored.
- Added provider-specific HERE caching to reduce commercial-provider cost/latency while avoiding persistent caching of Nominatim/OSM responses.
- Exact-address HERE results cache for 1 hour; general HERE discovery results cache for 5 minutes.
- Search cache, telemetry and rate-limit tables are RLS-protected and unavailable to anon/authenticated clients; only the backend service role can access runtime functions.
- Added retention cleanup support: expired cache rows, old rate-limit buckets and telemetry older than 30 days.
- Versioned all database migrations under `supabase/migrations`.

Current blocker to final Global Search completion:

- Configure the commercial `HERE_API_KEY` in the Supabase backend and run global acceptance/quality tests against the live commercial provider.
