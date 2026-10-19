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


## Workstream 5: Community Reports

Implemented on `feature/community-reports-v1`:

- Real Supabase-backed community report storage with RLS-protected tables.
- Supported report types: police, traffic, crash, lane closed, construction, road hazard, flooding, disabled vehicle and object on road.
- Type-specific TTL prevents stale incidents from lingering indefinitely.
- Nearby duplicate submissions of the same type are clustered within 100 m rather than creating repeated map incidents.
- Confirmation/dismissal votes are unique per hashed client and recompute confidence.
- Confidence starts at 0.35, increases with independent confirmations, decreases with dismissals and is capped to [0.0, 0.95].
- Automatic moderation rejects reports after sustained negative feedback (at least three dismissals and more than twice the confirmations).
- Active community reports are queried along the current route and merged into the existing provider-neutral road-intelligence pipeline.
- Community reports therefore use the existing route matching, alert prioritization, map rendering and Live rendering rather than a parallel incident UI.
- Map navigation exposes “Still there” / “Not there” feedback actions for community-backed alerts.
- While navigating, road events refresh every 90 seconds so newly submitted reports can appear without requiring a reroute.
- Submission/query/vote paths are rate limited and instrumented.
- `community_report_health_24h` exposes operational volume, voting, clustering, errors and latency.
- `scripts/production-community-reports-acceptance.ps1` validates submit, route query, confidence increase, duplicate clustering and dismissal.
- GitHub Actions now runs Android assemble, unit tests, lint and community-report production acceptance on pull requests to `production/platform-v1`.

Release validation:
- CI must pass before merge.
- Physical-device testing should confirm the report dialog, route-alert appearance, feedback buttons and refresh behavior during a real drive.


## Workstream 6: Human-quality Navigation Voice

Implemented on `feature/human-voice-v1`:

- Added an OpenMaps `voice` Edge gateway so cloud-provider credentials never ship in Android.
- Neural speech uses OpenAI `gpt-4o-mini-tts` with the `cedar` voice when `OPENAI_API_KEY` is configured server-side.
- The cloud request is constrained to the deterministic navigation phrase OpenMaps already generated; the provider is explicitly instructed not to add, remove, explain or paraphrase navigation instructions.
- Android now has a neural-first `HybridVoiceGuidanceEngine` with the existing Android TextToSpeech engine as a safety fallback.
- Near/immediate maneuver and rerouting prompts never wait on the network: cached neural audio is used when available, otherwise Android TTS speaks immediately.
- Far-stage maneuver prompts prefetch Near and Immediate variants so critical later prompts are normally already local.
- Neural audio is cached in the Android app cache by language + normalized phrase and bounded to 160 files.
- Common road abbreviations and US highway labels are expanded only for speech pronunciation; visible map/street labels are unchanged.
- Existing localized templates remain the source of truth for English US/UK, Spanish, French and Nigerian Pidgin.
- Settings disclose that navigation may use an AI-generated voice and that Android speech is the automatic fallback.
- Voice requests are rate limited and instrumented through `voice_telemetry` / `voice_health_24h`.

Credential/release gates:
- Configure `OPENAI_API_KEY` in the Supabase project before neural voice production acceptance.
- Android build, unit tests and lint must pass after Phase 5 lands in production.
- Physical-device validation must cover Bluetooth/car audio, interruption/ducking, screen lock, offline fallback and real street-name pronunciation.


## Workstream 7: Worldwide Localization

Implemented on `feature/worldwide-localization-v1`:

- Android per-app language configuration is enabled with declared support for English US, English UK, Spanish, French, Arabic and Hebrew.
- Full UI resource packs are present for Spanish, French, Arabic and Hebrew; English UK carries regional terminology overrides such as car park and petrol.
- Arabic and Hebrew use Android RTL layout support rather than a mirrored custom navigation implementation.
- Navigation units default to regional automatic behavior and can be overridden to Imperial or Metric in Settings.
- US/Liberia/Myanmar automatic behavior resolves to US-style imperial; the UK resolves to miles/yards/mph; other regions resolve to metric.
- Route preview, active Map navigation, Live navigation, destination/place/nearby distances, speed limits and road alerts use the same regional unit policy.
- Arrival time is formatted using the active app locale and local 12/24-hour conventions.
- Search, reverse geocoding, commercial routing, Valhalla fallback, Places and Android voice search receive the active app locale rather than a hard-coded US locale.
- Places passes the locale through the OpenMaps gateway to HERE and uses language-specific OSM `name:<lang>` tags when they exist; it never fabricates translated POI names.
- Neural/TTS navigation now supports Arabic and Hebrew in addition to English US/UK, Spanish, French and Nigerian Pidgin.
- Far/near spoken maneuver distances follow the selected measurement system: metric, US imperial or UK imperial.
- Non-English street/place names are preserved rather than run through English road-abbreviation expansion.
- The production `voice` and `places` Edge Functions have been upgraded for localization.
- `scripts/production-localization-acceptance.ps1` requires complete Spanish/French/Arabic/Hebrew string-key parity, the declared locale set and RTL wiring.
- Worldwide Places acceptance now sends regional locale tags for Houston, London, Paris, Lagos, Tokyo and Sydney.
- Voice production acceptance includes Arabic, Hebrew and metric-distance neural prompts.

Release validation:
- Android assemble, unit tests, lint and all production acceptance suites must pass before merge.
- Physical-device QA must switch the app through LTR and RTL languages and validate truncation, mirroring, Map/Live overlays, voice search and system app-language return behavior.
- Real driving QA should validate automatic unit selection in at least a US, UK and metric locale configuration.


## Production implementation Phase 8: Backend Service Layer

This implementation phase completes the cross-cutting backend work described as **Production
workstream 9** above. It does not replace the separate Production Map Platform & Cartography
workstream; that remains its own launch requirement.

Implemented on `feature/production-backend-gateway-v1`:

- Android now has one `OPENMAPS_BACKEND_BASE_URL` / `BACKEND_BASE_URL` configuration for the
  OpenMaps service layer. Endpoint-specific overrides remain available for rollback/migration, but
  the default topology is one backend origin with versioned service paths.
- One Supabase publishable key is named and treated as the OpenMaps **backend** client key rather than
  a geocoding-specific credential. The legacy geocoding property remains only as a configuration
  compatibility fallback.
- HERE, Mapbox, OpenAI and Supabase service-role credentials remain server-side. The Android app
  receives no commercial provider secret.
- A stable random anonymous install identifier is shared by all Android backend repositories. Edge
  functions hash that identifier before rate-limit accounting; the raw identifier is not persisted
  in the rate-limit tables. Network address is only the compatibility fallback for older clients.
- Reverse geocoding now uses the same database-backed abuse control as the other navigation
  services instead of being the one unmetered public endpoint.
- Existing per-service caching, provider failover, telemetry and cost controls remain in the
  provider-specific functions. Phase 8 deliberately does **not** add a thin Edge Function that
  calls other Edge Functions, avoiding an extra network hop and another failure surface.
- A `platform` control-plane endpoint exposes the OpenMaps backend API version, coarse service
  readiness and capability contract without exposing credentials or internal provider responses.
  Responses include `x-openmaps-api-version` and `x-openmaps-request-id` for operational
  correlation.
- `scripts/production-backend-gateway-acceptance.ps1` verifies authentication, control-plane
  contract/versioning, required capabilities, request correlation, unified Android configuration
  and the absence of server-only provider credential names from Android source/build configuration.
- GitHub Actions runs the backend gateway acceptance gate before the existing feature-specific
  production acceptance suites.

Security/operations verification:

- Runtime/cache/telemetry tables remain RLS-protected from anonymous/authenticated Data API access.
- The two public-schema `SECURITY DEFINER` rate-limit functions grant execute only to
  `service_role`/owner, not to anonymous or authenticated clients.
- Supabase security advisors show no high/critical finding for this backend work; current
  `rls_enabled_no_policy` notices describe intentionally service-role-only tables.
- Provider functions fail open only when the rate-limit accounting infrastructure itself is
  unavailable; provider/API failures continue to use the existing explicit Android fallbacks.

Phase 8 release gates:

- Android `assembleDebug`, unit tests and lint pass.
- Production backend gateway acceptance passes against the deployed `platform` endpoint.
- Worldwide localization, Community Reports, Trip Sharing, Places and Human-quality Voice
  acceptance suites remain green.
- The changed Edge Functions are deployed from the same Git commit being validated by CI.
- No Phase 8 merge occurs while any production acceptance gate is red.


## Production implementation Phase 9: Reliability & Observability

Implemented on `feature/reliability-observability-v1`:

- Added a provider-neutral `ReliabilityReporter` seam so navigation/Live code never depends on a
  specific analytics SDK.
- Added first-party `BackendReliabilityReporter` delivery through the OpenMaps backend instead of
  adding a third-party crash/analytics SDK dependency.
- Android now reports coarse operational outcomes for app launch, route request/success/failure,
  navigation start/failure/end/arrival, off-route confirmation, reroute trigger/success/failure,
  AR availability/readiness/fallback, and camera failure.
- Reliability events intentionally exclude coordinates, addresses, destination/search text,
  account identifiers and other trip content. The backend rejects sensitive metadata keys and stores
  only a SHA-256 hash of the anonymous per-install identifier already introduced in Phase 8.
- Added `AndroidCrashRecorder`: a fatal crash writes a minimal local envelope synchronously, then
  uploads it on the next launch. The envelope contains exception type, a SHA-256 stack fingerprint
  and a bounded set of class/method/line frames. Exception messages are not retained.
- Crash upload is best-effort and isolated from navigation. A telemetry outage never blocks routing,
  camera, AR, voice or application startup.
- Added the `reliability` Edge Function with publishable-key validation, database-backed per-install
  rate limiting and server-side metadata sanitization.
- Added `mobile_reliability_events` with RLS enabled and direct anon/authenticated access revoked.
- Added `mobile_reliability_health_24h` for launch/crash rate, route success, navigation starts,
  reroute success, arrival, AR readiness/fallback and camera failures.
- Added service-role-only `operations_health_24h`, composing the existing Search, Routing, Traffic,
  Places, Trip Sharing, Community Reports and Voice health views with mobile reliability health.
- The public `platform` control-plane endpoint advertises only that reliability telemetry is
  available; operational metrics themselves are not exposed to mobile clients.
- Added `scripts/production-reliability-acceptance.ps1` and made it a required Android CI gate.

Privacy and security properties:

- No raw install identifier is stored in `mobile_reliability_events`; only SHA-256 client hashes are
  persisted.
- No GPS coordinate, route geometry, search text, destination text, address, email, phone number or
  user name belongs in the reliability schema.
- Fatal exception messages are deliberately excluded because they may contain provider payloads,
  user-entered text or other data not appropriate for telemetry.
- The reliability table and operational health views are unavailable to anonymous/authenticated Data
  API roles.
- Supabase security advisors report no high/critical finding from this work. The
  `rls_enabled_no_policy` INFO notice is intentional for service-role-only operational tables.

Phase 9 release gates:

- Android `assembleDebug`, unit tests and lint pass.
- Reliability/Observability production acceptance passes against the deployed Edge Function.
- All prior production acceptance suites remain green.
- Deployed `reliability` and `platform` functions exactly match the PR source.
- No merge occurs while any production acceptance gate is red.

## Production implementation Phase 11: Privacy & Security Hardening

Implemented on `feature/privacy-security-hardening-v1`:

- Android explicitly blocks cleartext network traffic and uses a dedicated network-security
  configuration that trusts the system certificate store only.
- The existing Live safety acknowledgement now includes Google's required ARCore disclosure and the
  Geospatial sensor-data notice at activation time, plus the required Learn More destination.
- Settings permanently exposes the same ARCore/Geospatial disclosure and the OpenMaps retention
  summary so privacy information remains accessible after activation.
- Operational telemetry retention is capped at 30 days.
- Search/navigation rate-limit buckets are retained for at most 2 days.
- Expired search/places caches are cleaned daily.
- Finished/expired trip-share sessions are removed after a short post-session window instead of
  becoming a persistent location-history store.
- Expired community reports are removed after a short operational grace period.
- Supabase Cron runs `cleanup_openmaps_retention()` daily; the function is executable by
  `service_role` only.
- The retention function itself is `SECURITY INVOKER`, and no new public privileged function was
  introduced.
- CI now includes a Privacy/Security acceptance gate covering TLS-only networking, ARCore disclosure,
  retention policy, cleanup scheduling and Android secret wiring.

Supabase security advisors report no high/critical findings. Existing
`rls_enabled_no_policy` INFO findings are intentional for service-role-only operational tables.

## Production implementation Phase 12: Offline & Degraded Operation

Implemented on `feature/offline-degraded-operation-v1`:

- Successful routes are persisted locally for short-lived degraded reuse.
- A cached route is eligible only when it is fresh (maximum six hours), the requested origin remains
  near the cached origin, and the destination still matches closely.
- Cached routes are explicitly marked `isOfflineCached`; stale traffic timing, traffic delay and
  alternatives are stripped on restore so OpenMaps never presents yesterday's provider state as live.
- Map UI displays a persistent degraded-route notice while a cached route is active.
- Android monitors validated internet connectivity. When connectivity returns, a cached route is
  automatically refreshed from the live routing chain; active navigation reroutes in place while
  Route Preview performs a normal route refresh.
- Active navigation already preserves the existing route when a live reroute fails.
- Places retains its cache -> provider -> stale-cache -> explicit-failure chain.
- Human-quality Voice retains Android TextToSpeech as the local failure/offline fallback.
- Search, routing and Places already expose explicit network/timeout/degraded errors rather than fake
  success states.
- CI includes an Offline/Degraded acceptance gate and unit tests covering route-cache freshness,
  destination matching and removal of stale traffic metadata.

Provider-dependent release gate:

- Deterministic pre-download of a route's map-tile corridor is intentionally not enabled against the
  development OpenFreeMap source. Offline-region/prefetch behavior must be implemented and certified
  against the selected production tile provider's SDK/URL scheme, cache limits, redistribution terms
  and offline licensing. The route/navigation recovery work above does not claim offline-map coverage.


## Production implementation Phase 13: Android Release Hardening

Implemented on `feature/android-release-hardening-v1`:

- Active user-started navigation now owns a real Android location foreground service.
- The manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` and
  `android:foregroundServiceType="location"`, matching current Android foreground-service rules
  for navigation/location sharing.
- The service is started from the visible Start action, publishes an ongoing navigation notification
  and links the notification back to OpenMaps.
- Location collection continues while the app is backgrounded or screen-locked **only** during an
  Active/OffRoute/Rerouting navigation state; ordinary browsing still stops GPS at `ON_STOP`.
- Ending, stopping or arriving stops the foreground service and background location collection.
- Active route/destination state is persisted locally with a six-hour recovery TTL. On process
  recreation, OpenMaps waits for a fresh location fix, restores in conventional Map mode and resumes
  route progress rather than automatically reopening camera/AR.
- Recovery snapshots are deleted when the trip ends and Android backups are disabled.
- Live Trip Sharing's private owner capability can survive process recreation without plaintext
  storage: it is encrypted with a non-exportable Android Keystore AES-256/GCM key and cleared on
  stop/arrival/expiry.
- Unit tests cover active-navigation background location and process-recovery resumption.
- CI includes an automated Android Release Hardening acceptance gate.
- `docs/ANDROID_FIELD_VALIDATION.md` is the mandatory physical-device/road acceptance matrix.

Not claimable by automation:

- Actual screen-lock, background, memory-pressure process recreation, 60+ minute trip, Bluetooth,
  thermal/battery, real LaneLock, tunnel/GPS-drift and AR road-alignment behavior require a physical
  Android phone and real-road execution. Those rows remain NOT RUN until executed.


## Production implementation Phase 14: Regional Capability Matrix / Beta Readiness

Implemented on `feature/regional-capability-matrix-v1`:

- `config/regional-capabilities.json` is the machine-readable regional source of truth.
- `docs/REGIONAL_CAPABILITY_MATRIX.md` presents the same evidence for launch review.
- The initial launch matrix covers Houston, London, Paris, Lagos, Tokyo and Sydney.
- Capability state distinguishes VERIFIED, SUPPORTED, CONDITIONAL, FALLBACK, UNVERIFIED, BLOCKED and
  UNSUPPORTED instead of collapsing every provider feature into a misleading yes/no.
- Routing and traffic-aware ETA are VERIFIED in all six launch cities because production routing
  acceptance directly exercises each city with HERE and requires `trafficAware=true`.
- Places is VERIFIED in all six launch cities because production Places acceptance directly requires
  HERE commercial-provider participation and multi-category results in every city.
- Search is only VERIFIED where the current search acceptance has direct evidence; Tokyo/Sydney stay
  SUPPORTED until explicit search cases are added.
- LaneLock remains CONDITIONAL by design because it is segment/evidence dependent, not a country-wide
  entitlement.
- Live parking occupancy is UNSUPPORTED until a real occupancy provider exists.
- Tokyo voice/localization remains FALLBACK because Japanese UI/navigation voice is not implemented.
- Live AR, production maps and offline map corridors remain BLOCKED until their declared external
  dependencies and physical validation are complete.
- CI validates matrix completeness, legal statuses, evidence text, six-city routing/Places evidence
  and prevents premature promotion of the known blockers.

Public beta is intentionally reported as BLOCKED while any global blocker remains. The matrix is a
release-control artifact, not a marketing coverage claim.


## Production map provider activation: Stadia Maps

Approved production provider: **Stadia Maps Starter**, rendered with the existing MapLibre Native
engine. OpenFreeMap remains a development-only fallback when no production key is configured.

Implemented on `feature/stadia-production-maps-v1`:

- `STADIA_MAPS_API_KEY` activates the approved Stadia vector style automatically.
- Production provider verification is enforced by Gradle task
  `verifyProductionMapConfiguration`.
- MapLibre continues to own rendering while OpenMaps keeps its runtime Browse Day, Browse Night,
  Navigation Day and Navigation Night cartography.
- Production attribution is set to Stadia Maps / OpenMapTiles / OpenStreetMap.
- Active routes automatically create one MapLibre offline region using the same authenticated
  Stadia style URL.
- A new route, alternative or reroute replaces the previous OpenMaps-owned offline region.
- Route-corridor padding is conservative and maximum zoom decreases as route distance grows.
- Downloads stop at **90 MiB total resources**, leaving headroom below the provider's documented
  **100 MB/device** mobile tile-caching ceiling.
- Completed/ended trips delete the OpenMaps active-route region.
- Rapid reroutes use a generation guard so stale asynchronous region creation cannot leave duplicate
  downloads.
- `production-stadia-map-live-acceptance.ps1` validates the authenticated style and TileJSON
  endpoints once the production API key is available.

Remaining external activation gate:

- A Stadia Maps property/API key must be created under the approved subscription and supplied through
  `STADIA_MAPS_API_KEY`. The credential is intentionally not committed to source control.
- After the key is configured, run both `verifyProductionMapConfiguration` and
  `scripts/production-stadia-map-live-acceptance.ps1` before promoting map/offline-map capability
  from BLOCKED in the regional matrix.
