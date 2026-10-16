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
