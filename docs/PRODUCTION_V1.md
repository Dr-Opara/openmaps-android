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

4. Production Map Platform & Cartography
   - Production-grade global vector tile source with operational SLA or self-hosted infrastructure.
   - OpenMaps-owned day, night and navigation cartographic styles rather than shipping a development basemap unchanged.
   - Road hierarchy, highway shields, ramps, exits, labels, buildings, land use, water and POI density tuned by zoom and driving context.
   - Navigation-specific rendering for route casing, traveled route, alternate routes, congestion, incidents, closures and maneuver context.
   - Smooth consumer-grade camera, zoom, pitch, rotation and gesture behavior.
   - Regional visual QA across dense cities, suburbs, highways, rural roads, night driving and complex interchanges.
   - Map presentation receives its own production acceptance gate; backend correctness alone is not launch readiness.

5. Live Trip Sharing
   - Expiring opaque token.
   - Realtime location + ETA updates.
   - Viewer requires no account.
   - Stop sharing, auto-expiry, arrival cleanup, rate limiting and privacy controls.

6. Community Road Reports
   - Police, traffic, crash, lane closed, construction, road hazard, flooding,
     disabled vehicle and object on road.
   - Confirmation/dismissal, clustering, TTL, confidence, moderation and abuse controls.

7. Human Navigation Voice
   - Neural/cloud voice as primary.
   - Android TTS as offline/failure fallback.
   - Low latency, SSML/pronunciation controls, street-name handling and phrase caching.

8. Internationalization
   - App language separate from navigation voice.
   - Locale-aware units/time/number formatting.
   - RTL support.
   - Region-specific navigation terminology and street pronunciation.

9. Backend Service Layer
   - OpenMaps API gateway/service layer between clients and external providers.
   - Provider key protection, caching, failover, rate limiting, observability and cost controls.

10. Reliability and Observability
   - Crash/error reporting.
   - Search zero-result rate.
   - Search/routing/provider latency.
   - Route success and reroute rates.
   - Trip share health.
   - AR readiness/anchor/render health.
   - Provider failover telemetry.

11. Privacy and Security
    - Minimal location retention.
    - TLS everywhere.
    - Short-lived trip-share tokens.
    - User-controlled sharing.
    - Server-side secrets.
    - Retention/deletion policy and regional privacy requirements.

12. Offline/Degraded Operation
    - Cached route/map corridor.
    - Last-known navigation state.
    - Offline/fallback voice.
    - Clear degraded-service behavior.

13. Regional Capability Matrix
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


## Phase 3: Production routing and traffic

Implemented on `production/platform-v1`:

- OpenMaps `route` Edge Function uses HERE Routing v8 as the traffic-aware commercial primary.
- Android retains Valhalla as an explicit last-resort degraded routing fallback.
- Route timing preserves traffic-adjusted duration, free-flow/base duration, typical duration and live delay without fabricating unavailable values.
- HERE provider-returned alternatives are normalized into `RouteAlternative`, surfaced in Route Preview and can be selected instantly without another provider request.
- Provider exit/signpost guidance is carried through `RouteManeuver` and rendered consistently in Map and Live.
- Route progress now performs heading-aware snap-to-road matching and exposes the projected road coordinate plus High/Medium/Low/OffRoute match confidence.
- HERE Traffic incidents/closures are fetched through the OpenMaps backend and merged with the existing static OSM road-intelligence fallback.
- High/severe live crashes or closures ahead can trigger one traffic-aware recalculation; dedupe prevents repeated reroute loops and the old route remains usable if replacement fails.
- HERE traffic-aware route timing populates `TrafficCondition`, so +X minute traffic labels are shown only when backed by real provider timing.
- LaneLock is wired through a dedicated provider-neutral `LaneGuidanceRepository`.
- LaneLock now uses OSM `turn:lanes` metadata plus Mapbox Directions lane arrays when Mapbox is configured.
- The gateway requires maneuver-compatible lane evidence; matching OSM + Mapbox evidence is high confidence, one trustworthy source is medium confidence, and conflicting evidence is hidden.
- LaneLock renders the physical provider-returned lane count left-to-right and never infers lane geometry from the route line.
- HERE Route Matching is no longer a LaneLock dependency.
- Routing, live traffic and lane endpoints are protected by per-client, per-endpoint database-backed rate limits.
- Routing and traffic telemetry record request success/error, latency, traffic awareness, route distance/duration, alternatives and incident counts.
- 24-hour `routing_health_24h` and `traffic_health_24h` views expose operational health.
- Runtime telemetry/rate-control tables are RLS-protected from anon/authenticated clients.
- Worldwide automated acceptance covers Houston, London, Paris, Lagos, Tokyo and Sydney plus live traffic and lane-provider capability.
- Android unit coverage includes traffic parsing, alternatives, exit/signpost guidance, heading-aware map matching, map-match confidence and lane-guidance parsing/presentation.

Final release gates before this workstream is marked complete:

- Local Android `assembleDebug`, unit tests and lint must pass on the production branch.
- `scripts/production-routing-acceptance.ps1` must pass against the deployed HERE services.
- The OSM/Mapbox lane gateway must pass acceptance; a valid `no_trustworthy_lane_guidance` response is an honest supported state.
- A physical-device drive must validate noisy-GPS map matching, off-route/reroute behavior, Map↔Live continuity, real incident presentation and LaneLock only when provider lane evidence is present and trustworthy.

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

Global Search production status: **complete**. The commercial HERE credential is active, production telemetry confirmed live HERE responses, and the automated worldwide acceptance suite passed.


## Workstream 3: Global Places

Implemented on `feature/global-places-v1`:

- Added an OpenMaps `places` Edge Function with HERE Browse as the commercial global primary.
- Added OSM/Overpass aggregation inside the gateway and retained direct Android Overpass as a last-resort gateway fallback.
- HERE and OSM results are normalized into the existing provider-neutral `Place` model.
- Cross-provider duplicate suppression uses category, normalized name/address and an 80 m proximity gate.
- Global categories now include restaurants, coffee, hotels, grocery, pharmacy, parking, gas, EV charging, banks, ATMs, convenience, medical, entertainment, shopping and education.
- Education covers schools, colleges, universities and kindergartens through HERE and OSM.
- Android now uses `BackendPlaceRepository` before `OverpassPlaceRepository`.
- Route-corridor results are filtered back to the actual corridor rather than returning every POI in the bounding search radius.
- The places gateway is protected by the shared database-backed per-client rate limiter at 60 requests/minute.
- Server-side places caching uses a rounded location/category/radius key with a five-minute TTL.
- Places telemetry records category set, result count, provider participation, latency, zero-result/error outcome, fallback use and cache hits without storing raw latitude/longitude.
- `places_health_24h` exposes launch-facing health metrics.
- `scripts/production-places-acceptance.ps1` covers Houston, London, Paris, Lagos, Tokyo and Sydney.

Global Places release gates:

- Android `assembleDebug`, unit tests and lint pass on the feature branch.
- Worldwide places acceptance passes against the deployed production gateway.
- HERE must participate as the commercial provider in each acceptance city.
- Physical-device validation confirms nearby discovery, route-corridor filtering, category filters and graceful cached/fallback behavior.
- Provider limitations for live parking occupancy, imagery, reviews and unsupported metadata remain explicit; OpenMaps must not invent them.


## Production Map Platform & Cartography acceptance principle

OpenMaps must remain a credible premium consumer navigation product even with Live AR turned off. The conventional map experience must meet the usability, visual hierarchy, responsiveness and navigation clarity expected from leading consumer map products while retaining a distinct OpenMaps identity.

The current OpenFreeMap Liberty default is a development surface, not the final production cartographic product. MapLibre may remain the renderer, but production tile infrastructure, OpenMaps-owned styles, navigation rendering and visual QA are separate launch requirements.


## Workstream 4: Live Trip Sharing

Implemented on `feature/live-trip-sharing-v1`:

- Added a real Supabase-backed trip-sharing session store.
- Public viewer links use random share capability tokens stored only as SHA-256 hashes server-side.
- Android receives a separate private owner capability token for update/stop operations; the owner token is never embedded in the public URL.
- Sessions are time-bounded to a minimum 5 minutes and maximum 24 hours.
- Navigation streams current location, heading, speed, remaining distance and remaining ETA to the backend on a throttled 5-second cadence while sharing is active.
- Tapping Share again reuses the active link instead of creating duplicate sessions.
- Arrival automatically marks the session arrived, stops further device updates, and removes current-location exposure from the public payload.
- Explicit navigation exit revokes the active share session.
- Expired or manually stopped links return an ended response rather than the last known location.
- The public viewer page polls the same capability URL every 3 seconds and shows destination, ETA, remaining distance, status and last update.
- RLS blocks direct anonymous/authenticated table access; all session access is through the Edge gateway.
- Create/update/stop/view paths use rate limiting and telemetry.
- `trip_share_health_24h` provides operational metrics.
- `scripts/production-trip-sharing-acceptance.ps1` validates create -> viewer -> live update -> arrival shutdown and explicit stop/revocation.

Remaining release validation:
- Android build/unit/lint on the feature branch.
- Production acceptance script against the deployed gateway.
- Physical-device validation during a real trip, including screen lock/background behavior and link expiration.
