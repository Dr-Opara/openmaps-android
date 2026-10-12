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

## Phase 6: AR localization, Done (emulator fallback paths only — needs a physical device)

- ARCore `com.google.ar:core` 1.56.0, Geospatial (Earth) mode, runtime support-checked, `optional` in the manifest so the app still installs without it
- `SpatialLocalizationEngine` seam + provider-neutral `SpatialLocalizationState`/`SpatialPose`; only `spatial/arcore` imports `com.google.ar.core`
- **Camera decision: ARCore replaces CameraX rather than running beside it.** One camera owner at a time; ARCore renders its own preview via `GLSurfaceView`, and CameraX is the fallback. `SharedCamera` was rejected as unused complexity
- Provider choice is **state, not a type check**: a camera-free `probeAvailability()` decides before any camera opens, plus a runtime guard that hands the camera back to CameraX if AR fails mid-session (an earlier revision black-screened here — caught on the emulator and fixed)
- Localization quality from real accuracy values (`Unavailable/Acquiring/Poor/Fair/Good`), configurable thresholds, missing accuracy treated as unknown rather than perfect
- `GeoProjector`: the geographic -> screen projection seam Phase 5 deferred, now pure, GL-free and unit-testable, returning `visible`/`onScreen` so Phase 7 never draws an arrow behind the driver
- Quiet localization chip ("Locating… / Improving accuracy… / Ready"); silent entirely when AR is unsupported or failing
- Debug-build-only diagnostics (AR state, h/v/heading accuracy, coordinates); no location is ever logged continuously
- 50 new unit tests (175 total). **Emulator validation covers the fallback paths only** — the test AVD has no ARCore and no AR hardware, so Earth tracking, pose and heading are unverified.

## Phase 8 — AR route arrows on ARCore world anchors (complete, device validation pending)

Phase 8's arrow renderer existed before Phase 7 was real, and was therefore built against
projection-only placement. Phase 7 has since been repaired to create genuine geospatial anchors, and
Phase 8's **spatial placement seam** — and only that seam — was re-pointed at them.

- **Phase 7 repair.** Commit `fec84b2` was labelled "Complete Phase 7 AR route anchoring" but contained
  no anchor code at all: no `Earth.createAnchor`, no `Anchor`, no world transform, no anchor
  lifecycle. The added `core/anchor` layer (pure models, sampler, policy manager) plus
  `anchor/ArCoreAnchorBackend` is the only file that touches `com.google.ar.core` for anchors.
- **The churn bug, and the design that fixes it.** Anchors were first keyed on route vertex index and
  spaced from the driver's position, which re-created the entire anchor set on every location update —
  the same class of shimmer that makes world-locked content unusable. Making density a function of
  distance-ahead did not fix it either, because a forward-dependent rule must be seeded from somewhere
  and the seed moves with the window. The shipped rule is stateless and absolute: anchor *n* sits at
  `n × anchorGridMeters`, and density is a modular rule on that grid index, tightened only around the
  next maneuver's *absolute* route position. Driving adds anchors at the far end of the window and
  retires them at the near end.
- **Sampling logic unchanged.** `ARArrowSampler` still owns route sampling, near/far spacing, turn
  density, the near cut-off, the visible cap and the tangent heading, and still runs on both paths.
  Only placement changed.
- **Placement is anchored-first.** `ARArrowPipeline.update` prefers `AnchoredArrowProjector` whenever a
  tracked anchor and a camera world transform both exist, and only then falls back to
  `GeoProjector`-based per-frame lat/lng projection. `usingAnchors` and `degradedReason` state which
  path is live and why, so degraded mode is never mistaken for working anchoring.
- **Interpolation between sparse anchors** is done in world space, so it is a real line in ARCore's
  frame. Arrows beyond the anchored window are dropped rather than extrapolated.
- **Camera orientation is used in full** (position *and* quaternion). A position-only projection would
  leave arrows pinned to screen centre when the phone turns — the classic "not actually world-locked"
  symptom.
- **Altitude is a fixed 0.35 m lift, not road elevation.** Terrain/rooftop anchors exist in the ARCore
  API but are far less reliable than geospatial ones, so this is documented as a limitation rather than
  faked. Arrows will float or sink on real gradients.
- 80 new unit tests (304 total, 0 failures), all pre-existing Phase 1–8 tests preserved.
- **Emulator validation covers fallback and lifecycle only.** The test AVD has no ARCore package, so
  `Earth.createAnchor` was never executed at all; only the CameraX fallback and `AR: Idle` were
  observable. No crashes across launch, 5× Map↔Live, 3× background/foreground, and
  navigation/off-route/reroute/stop, and the camera open count stayed at 1 throughout.
- **Physical ARCore device validation is PENDING.** No ARCore-capable device was available. Road
  alignment, anchor stability while walking, turn continuity and drift are all unobserved in the real
  world.

## Phase 9: Contextual places + parking + fuel/EV, Done (needs device check beyond the emulator)

- `core/places`: provider-neutral `Place`/`PlaceCategory`/`PlaceMetadata`/`PlaceException` models, a `PlaceRepository` seam (`nearbyPlaces`, `placesAlongRoute`) — same shape as `GeocodingRepository`/`RoutingRepository`
- `OverpassPlaceRepository`: public Overpass API over OSM data, development-only (no SLA — see [ARCHITECTURE.md#place-data-provider](ARCHITECTURE.md#place-data-provider)); Overture Maps was evaluated and rejected (Parquet-over-blob-storage, not a request/response nearby-search API)
- `PlaceCategoryMapper`: OSM tag <-> 8 categories (restaurant, coffee, hotel, grocery, pharmacy, parking, gas, EV charging); unsupported tags/categories are skipped, never guessed
- `PlaceCache` (TTL + location bucketing) and `RouteCorridor` (nearest-segment distance, ahead/behind, corridor filter/approximation) — both pure Kotlin, both unit-tested
- `PlaceRanking`: deterministic category-weight/distance/ahead-or-behind scoring, no ML; utility categories (parking/gas/EV) weighted higher only while navigating
- Map rendering: `MapEngine.setPlaces` + a category-colored `CircleLayer` (not `SymbolLayer` — the same icon-rendering limitation documented for the destination marker applies to 8 category icons too) below the route/destination layers; tap-to-select via `queryRenderedFeatures`
- Live rendering: `LivePlaceProjector` reuses the Phase 6 `GeoProjector` seam for a restrained, capped (3) set of screen-space labels — only while AR is `Tracking` with a pose; no labels in the CameraX fallback, by design ("do not fake precision")
- Place details sheet + category filter chips (client-side filter only, never re-triggers a fetch) + "Navigate" reusing the existing `onDestinationSelected` path — no second routing system
- Parking (type/access/fee), gas (brand), EV charging (operator/network, connectors) — every field only shown when OSM's tags actually state it; no invented availability, pricing or occupancy
- 58 new unit tests. `assembleDebug`, `testDebugUnitTest` and `lint` all pass clean (0 lint issues) on top of Phase 7/8's own suite — see the total below. **Needs a physical device** for Live label placement/readability while moving and a real emulator/device run of the fetch -> marker -> tap -> navigate flow, same open item as Phase 6/7's AR path.

## Next, not started (awaiting approval)

Tentative order; each needs its own scope decision.

1. **Physical-device validation of Phases 7/8.** This is now the highest-priority open item: every
   spatial claim about anchoring is currently unverified against real ARCore hardware.
2. **Production basemap**: pick and host the tile source (PMTiles/Protomaps or self-hosted vector tiles), glyphs and sprites; flip `devOnly` to false; dark navy style to match the brand.
3. **Production geocoding**: move off the public Nominatim instance (self-hosted Nominatim/Photon or a contracted provider) before shipping.
4. **Production routing**: move off the public Valhalla demo instance (self-hosted Valhalla or a contracted provider) before shipping.
5. **Turn-by-turn guidance**: done in Phase 4; voice guidance is still open.
6. **Production places**: move off the public Overpass instance (self-hosted Overpass or a contracted places provider) before shipping — done in Phase 9, production provider still open.
7. **Later layers**: traffic, road hazards, weather, lane guidance, speed-limit intelligence (Phase 10).

## Phase 10 prerequisites

Traffic, road hazards, weather and lane guidance. Available now: the `core/places` pattern itself
(provider-neutral model, caching, corridor filtering, ranking) as a template for a second
contextual-data domain; `RouteCorridor` already generalizes over "geographic things near a route";
`MapEngine.setPlaces` and `LivePlaceProjector` are the Map/Live rendering precedents a hazard/traffic
layer would follow (a parallel `setHazards`-style seam, not a shared one — different data, different
priority rules). Still needed: any live traffic/incident/weather data source, lane-level geometry,
and a priority model that can rank a hazard against a maneuver, not just against another POI.

## Phase 9 prerequisites

**Historical note:** this section originally read "Not started" when written at the end of Phase 8.
Phase 9 is now **done** — see the "Phase 9: Contextual places + parking + fuel/EV" entry above. Kept
here, unedited otherwise, as the as-of-Phase-8 planning record (same convention as the "Historical
note" under Phase 7 prerequisites below).

Available at the start of Phase 9: the `ArOverlayRenderer` extension point on
`ArCoreSpatialLocalization` (any overlay can draw after the camera without the AR engine knowing what
it is), the `MapViewModel.onRouteReplaced` / `onNavigationEnded` trip-lifecycle callbacks, and the
`RouteAnchorManager` / `ARArrowPipeline` derived-state pattern. Phase 9 ended up **not** using the
`ArOverlayRenderer`/anchor path for places: place labels are plain Compose overlays positioned via
the Phase 6 `GeoProjector` seam (2D screen-space, not world-anchored) — simpler, and sufficient for
informational text/distance labels rather than safety-critical world-locked content. It did reuse
`onRouteReplaced`/`onNavigationEnded`-style lifecycle wiring in spirit (`MapViewModel` drives
`fetchPlacesAlongRoute` from the same route-ready/reroute points).

What was actually needed and built: a place repository (`core/places/PlaceRepository`), a
`MapUiState` extension (`placesState`/`placeCategoryFilters`/`selectedPlace`/`visiblePlaces`), and a
projection seam that works without full AR anchoring (`LivePlaceProjector`, wrapping `GeoProjector`).

## Phase 7 prerequisites

Road-anchored AR arrows. Available now: `SpatialPose` (position + device-facing heading + accuracy +
quality), `GeoProjector.project(...)` for the geographic -> screen transform, `RouteResult.geometry`,
`LiveHudState`, `NavigationState`, and the AR `GLSurfaceView` renderer that already owns the GL context
and per-frame pump.

**Historical note:** when Phase 8 was first built, this section read "Still needed: AR anchor creation,
world-locked GL drawing … all Phase 7, deliberately not started" — which was true. The `fec84b2`
commit message claiming Phase 7 was complete was inaccurate. Both are superseded by the Phase 8 entry
above.

Still not implemented even after the Phase 7 repair: Streetscape geometry and terrain/rooftop
anchoring.

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
- Which production places provider, and at what volume (same category of question as tile/geocoding/routing)?
- Should places along a long route refresh as the trip progresses, or is the one-time, capped-radius corridor query (current behaviour) sufficient in practice?
- Should category filter preferences persist across sessions (currently reset to "all categories" on each app start)?
