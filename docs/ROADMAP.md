# OpenMaps Roadmap

Status key: **Done** (implemented), **Needs device check** (implemented, not yet run on a device), **Not started**.

OpenMaps is a United States-only, Android-first navigation product. Later phases below are directional only and need to be confirmed one at a time; none of them has been started.

## Phase 0: Foundation, Needs device check

Rebuilt from scratch in a remote environment (the original Phase 0 code was not available).

- Android project: Kotlin, Compose, Material 3, Gradle Kotlin DSL, version catalog
- OpenMaps design system: dark navy, white type, electric blue accent, glass controls
- Splash (system splash + in-app brand splash), adaptive launcher icon
- Home/search screen placeholder, Map screen container, Live placeholder, Settings
- Map / Live toggle
- Location layer: `LocationRepository` on the platform `LocationManager`, permission model, status model
- `ViewModel` + `StateFlow` architecture, manual DI

## Phase 1: Map foundation, Needs device check

- Real MapLibre Native map (pan, zoom, rotate, pitch)
- Blue current-location indicator fed by our location layer
- First-fix centring; no forced re-centring; animated recenter button
- Compass control (visible only when rotated)
- Location states with UI: loading, denied, permanently denied, GPS off, unavailable
- "Map unavailable" state with retry
- `MapStyleProvider` abstraction; default style is **development only**
- `MapEngine` abstraction with the MapLibre implementation
- Search placeholder ("Where are you going?" -> "Search destinations") with no geocoder
- Collapsed bottom panel with no fabricated route data
- Documentation (this folder)

Phase 1 build/test/lint validated locally (`gradlew assembleDebug testDebugUnitTest lint`) and manually confirmed on a Pixel 8 API 35 emulator. Open: a decision on the production tile source.

## Phase 2: Destination search + geocoding, Done (needs device check beyond the emulator)

- `GeocodingRepository` abstraction + `SearchResult`/`GeocodingException` models (pure Kotlin)
- `NominatimGeocodingRepository`: OSM Nominatim, US-restricted, development-only (no SLA, rate-limited — see [ARCHITECTURE.md#geocoding](ARCHITECTURE.md#geocoding))
- Real destination search UI: debounced query, loading/empty/error states, result list
- Recent searches: persisted (`SharedPreferences`, capped at 8, deduplicated), reopenable from the search screen
- Destination marker on the map (`MapEngine.setDestination`), camera fly-to, compact destination summary with clear action
- Replacing/clearing a destination correctly replaces/removes the marker (`GeoJsonSource.setGeoJson` always replaces wholesale)
- Unit tests: response parsing, recent-search dedup/cap, `SearchViewModel` debounce/cancellation/state transitions, `MapViewModel` destination selection/replacement/clearing
- Documentation (ARCHITECTURE.md: Geocoding, Search and destination flow, Destination marker sections)

Validated end-to-end on the Pixel 8 API 35 emulator, including live network calls to Nominatim. Known limitation: the destination marker renders as a plain circle, not a custom pin icon — see [ARCHITECTURE.md#destination-marker](ARCHITECTURE.md#destination-marker).

## Phase 3: Routing engine, Done (needs device check beyond the emulator)

- `RoutingRepository` abstraction + `Coordinate`/`RouteResult`/`RouteBounds`/`RoutingException` models (pure Kotlin)
- `ValhallaRoutingRepository`: public Valhalla demo instance, `costing=auto`, development-only (no SLA — see [ARCHITECTURE.md#routing](ARCHITECTURE.md#routing))
- Route requested automatically when a destination is selected and a location fix is available; waits (does not invent an origin) if it isn't, and resumes automatically once one arrives
- Route line on the map (`MapEngine.setRoute`, `GeoJsonSource` + `LineLayer`, brand blue, below the destination marker)
- One-time camera fit to the route's bounds on success; no active-navigation camera tracking
- Compact route summary (distance + ETA) in the bottom panel; loading/waiting-for-location/error states all have their own short copy, no raw HTTP errors
- Replacing/clearing a destination correctly replaces/removes the route, same `GeoJsonSource.setGeoJson`-replaces-wholesale mechanism Phase 2 established for the marker
- Unit tests: polyline decoding, response parsing (including a response captured from a live call), `MapViewModel` route request/replace/cancel/clear/error-mapping
- Documentation (ARCHITECTURE.md: Routing, Route flow, Route line sections)

Validated end-to-end on the Pixel 8 API 35 emulator with live calls to the Valhalla demo instance: a nearby destination produced a rendered route line, correct distance/ETA, and a one-time camera fit; a cross-country destination correctly surfaced "No route found" (the demo server's own limits, not a bug); replacing and clearing both worked with no stale state; Map/Live switching with an active route survived with no crash. Known limitation: no custom route styling beyond a solid line (no casing/halo); revisit if the brand wants more visual weight on the route.

## Phase 4: Turn-by-turn navigation, Done (needs device check beyond the emulator)

- Maneuver parsing from Valhalla's `maneuvers` array (Phase 3 discarded it), mapped to a provider-neutral `ManeuverType`; per-leg shape-index offsets so multi-leg routes index correctly
- `NavigationState` lifecycle (`Idle | Ready | Active | OffRoute | Rerouting | Arrived | Error`) wrapping `RouteProgress`
- `RouteProgressTracker`: snaps a location onto route geometry; current/next maneuver, distance to next, remaining distance/time, monotone (never regressing) progress
- Conservative off-route detection: a 75 m threshold **plus** 2-sample confirm / 5-sample reroute hysteresis, so GPS noise never costs a network round trip
- Rerouting that keeps the old route displayed; a failed reroute returns to `OffRoute` with a one-time event instead of dead-ending the trip
- Arrival detection requiring both ≤10 m remaining and ≥99% progress
- Navigation camera: targets 110 m *ahead* on the route (capped 8 m from the end), bearing from device heading -> route direction -> held bearing when far off route; manual pan suspends follow, recenter resumes
- Traveled/remaining route split (`openmaps-route-traveled-*`, muted slate under the accent line)
- Maneuver banner, trip summary, explicit start/stop; phase-1 map behaviour preserved (verified by test)
- 39 new unit tests (103 total). Validated end-to-end on the Pixel 8 API 35 emulator with live Valhalla routing.

## Phase 5: Live camera mode, Done (needs device check beyond the emulator)

- CameraX 1.4.2 rear-camera preview behind a `RearCameraEngine` interface — the only file importing CameraX is `CameraXRearEngine`, mirroring how `MapEngine` isolates MapLibre
- **Live does not own navigation**: it derives a HUD purely from the existing `MapUiState.navigationState`, so Map <-> Live preserves the trip and never restarts routing
- `LiveHud.state`: a pure `NavigationState` -> HUD mapping, so the whole guidance surface is unit-tested without a camera
- Camera permission modelled exactly like location permission (including the persisted "already asked" flag for permanently-denied), requested only when Live is opened and re-checked on resume
- Every CameraX failure classified into `CameraFailure` and rendered as an explanation — no crash, no bare black screen, always a way back to Map
- Lifecycle-bound camera: closes when the app backgrounds, released explicitly on leaving Live (no leak across repeated switches)
- HUD: maneuver arrow + distance + instruction + "Then …", remaining distance/time, destination name; directional scrims instead of blurred glass; "Rerouting…"/"Off route" transient states suppress stale figures; arrival card
- Fixed a Phase 4 bug found while building the HUD: the maneuver banner paired the *next* maneuver's instruction with the *current* maneuver's distance. Both surfaces now agree.
- 22 new unit tests (125 total). Validated end-to-end on the Pixel 8 API 35 emulator with a live rear-camera preview, permission grant/deny/revoke, live reroute while in Live, arrival in Live, 8 rapid Map <-> Live transitions, and background/foreground cycles.

## Next, not started (awaiting approval)

Tentative order; each needs its own scope decision.

1. **Production basemap**: pick and host the tile source (PMTiles/Protomaps or self-hosted vector tiles), glyphs and sprites; flip `devOnly` to false; dark navy style to match the brand.
2. **Production geocoding**: move off the public Nominatim instance (self-hosted Nominatim/Photon or a contracted provider) before shipping.
3. **Production routing**: move off the public Valhalla demo instance (self-hosted Valhalla or a contracted provider) before shipping.
4. **Turn-by-turn guidance**: done in Phase 4; voice guidance is still open.
5. **Live / camera-first view**: camera preview and HUD are done in Phase 5; AR localisation is the next step (Phase 6).
6. **Later layers**: traffic, parking, richer place intelligence.

## Phase 6 prerequisites

AR localisation, and what is already in place for it:

- Camera feed — `RearCameraEngine` binds a `Preview` use case to a `ProcessCameraProvider`; an `ImageAnalysis` use case attaches to the same bind. Phase 5 deliberately added **no** analyzer, since nothing consumed it yet.
- Current location, device heading, route geometry, current/next maneuver, navigation-active state — all already flow to Live from the single `MapViewModel`.
- Lifecycle hooks — `RearCameraHost` already binds to the composition lifecycle and releases on dispose, so an analyzer needs no new lifecycle work.
- Overlay seam — the HUD is a pure state-to-UI mapping, so an AR overlay composes beside it without touching that mapping.
- **Still needed**: a camera-frame -> screen-coordinate projection seam (`latLngToScreen`-style) for anchoring, plus a device-capability decision (ARCore vs. non-AR fallback). Phase 5 deliberately did not declare the projection API, to avoid speculative surface.
- Open question carried from Phase 5: the emulator's synthetic rear camera cannot validate real AR tracking — a physical device is required for Phase 6.

## Phase 4 prerequisites

Before turn-by-turn can start, these Phase 3 decisions need to be either confirmed or revisited:

- `RouteResult.geometry` (a decoded `List<Coordinate>`) is already available as the maneuver/tracking input — no new route-fetching plumbing needed, turn-by-turn can consume `MapUiState.routeState`'s `Ready.route` directly.
- Valhalla's response includes a full `maneuvers` array per leg (instructions, street names, bearings) that Phase 3's parser deliberately **discards** (`ValhallaResponseParser` only reads `shape`/`summary`) since maneuver UI was explicitly out of scope. Re-parsing that array is the natural starting point for turn-by-turn instructions — it does not require a different API call.
- Phase 3 requests a route **once** per destination selection. Turn-by-turn needs re-routing as the user's position diverges from the route (or periodically) — decide the trigger (distance-off-route threshold vs. a timer) and whether it reuses `requestRoute()` or needs its own path.
- The route line is a `CircleLayer`-adjacent `LineLayer` with no directionality styling. Active navigation typically wants a "traveled" vs. "remaining" visual split, which means either two layers (consumed/remaining) or a data-driven line-gradient expression — a MapLibre styling decision, not an architecture one.
- No active-navigation camera mode exists yet (Phase 3's `FitBounds` is one-time only). Turn-by-turn needs a follow/tilt camera mode — likely a new `MapCommand` and probably the point where `Live` stops being a placeholder.
- Pick the routing engine/data source for maneuvers specifically if it differs from the Phase 3 routing decision (Valhalla already returns maneuvers, so by default this is the same provider unless a reason emerges to split them).

## Open questions

- Which production tile host, and what volume do we expect at launch?
- Which production geocoding provider, and at what volume?
- Which production routing provider, and at what volume?
- Is the permission prompt on first launch (current behaviour) the desired onboarding, or should a pre-prompt explain it first?
- Do we want a persisted camera across process death (currently restored only across configuration changes)?
- Should recent searches sync across devices/accounts, or stay per-device (current behaviour)?
- Should a route auto-refresh if the user's location updates significantly while one is `Ready` (currently it does not re-request after the initial fetch)?
