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

## Phase 10: Road intelligence (hazards, weather, speed limits, alerts), Done (needs device check beyond the emulator)

- `core/roadintelligence`: provider-neutral `RoadEvent`/`RoadEventType`/`RoadEventSeverity`,
  `TrafficCondition`, `WeatherCondition`, `SpeedLimitInfo`, `LaneGuidance`/`Lane`, a `RouteAlert`
  sealed type + `RoadAlertPriority`, and a `RoadIntelligenceRepository` seam (`roadEventsAlongRoute`,
  `speedLimitNear`, `weatherNear`) — same shape as `core/places`/`core/routing`
- `DefaultRoadIntelligenceRepository`: Overpass API for hazards (`highway=construction`/
  `construction=*`/`hazard=*` tags only — OSM has no crash/debris/closure signal) and speed limits
  (nearest way's `maxspeed`, never inferred from a non-numeric value); Open-Meteo for current
  driving-relevant weather (free, no API key, no account) — see ARCHITECTURE.md#road-intelligence-phase-10
  for exactly what each can and cannot claim
- **No live traffic-congestion source** — the model/seam exist so one can be plugged in later, but
  none is hardcoded in; **lane guidance has a real, tested OSRM-shape mapper
  (`OsrmLaneGuidanceMapper`) with nothing to feed it**, since Valhalla's native `/route` response (what
  this app requests) carries no lane-level data at all
- `RoadEventRouteMatcher`: corridor filter + cumulative along-route distance via proper segment
  projection (not a chord-distance approximation); `RoadAlertActivation`: per-type approach
  distances + behind-driver suppression; `RoadAlertHysteresis`: two-tick confirm/exit debounce
  generalizing `OffRouteDetector`'s pattern so alerts never flicker on GPS noise
- `RoadIntelligenceCache`: three independently-TTL'd bucketed caches (hazards 3 min, weather 20 min,
  speed limit 30 min), same shape as `PlaceCache`
- `UserLocation.speedMetersPerSecond`: a direct `Location.getSpeed()` reading (never derived from
  consecutive fixes), kept distinct from the posted speed limit everywhere both are shown
- Map rendering: `MapEngine.setRoadEvents` + a severity-colored `CircleLayer`, anchored above places
  but below route/destination; `RoadAlertBanner` — a compact top-priority-alert + speed chip row
- Live rendering: `LiveRoadAlertHud` — at most one hazard/weather line plus one speed line, below
  the maneuver card, never more regardless of how many alerts qualify (Live priority: AR arrows >
  maneuver guidance > safety alerts > lane guidance > speed info > places)
- Rerouting is consumed, never triggered, by this layer: `RoadAlertHysteresis` resets and hazards
  refetch on a successful reroute, exactly like places already do
- 86 new unit tests (448 total). `assembleDebug`, `testDebugUnitTest` and `lint` all pass clean (same
  40-warning baseline as Phase 9, zero new warnings)
- Validated end-to-end on the Pixel 8 API 35 emulator: launch, search, live Valhalla routing + live
  Overpass places, Start navigation, camera-permission grant flow in Live, Live HUD, Map↔Live↔Map
  switching while navigating — no crashes. The tested route/location had nothing to surface (no
  tagged construction, no sub-"Moderate" weather, no tagged `maxspeed` way in range), so an actual
  alert rendering live was not separately observed — worth a specific follow-up

## Phase 12A — physical device defects, local discovery, Route Preview UX (code complete; AR blocked on API provisioning)

Picks up directly from Phase 12's "BLOCKED on hardware" status: a physical Samsung device (Android
16, ARCore 1.57.262640093) became available mid-phase, so this phase includes real on-device
findings, not just unit tests — the first since Phase 5.

- **AR root cause: four distinct issues, found one at a time on real hardware, not one bug.** See
  ARCHITECTURE.md's "AR stuck at Idle" for the full trace. Two were real app-code bugs, now fixed:
  (1) a transient ARCore-availability probe result (`UNKNOWN_CHECKING`) was treated as permanently
  unsupported — `SpatialAvailability.Checking` plus a bounded re-probe in `LiveViewModel` fixes it;
  (2) `ArCoreSpatialLocalization` was constructed with the `Application` context, from which
  `findActivityOrNull()` can never resolve an `Activity` — confirmed 100% reproducible on-device,
  fixed by resolving the Activity from the `LifecycleOwner` passed to `start()` instead; (3) ARCore's
  Geospatial mode requires `com.google.android.gms:play-services-location` linked into the binary,
  which was missing — added. The fourth is **not an app-code bug**: no Google Cloud project has the
  ARCore Geospatial API enabled with this app's package name + signing certificates registered, so
  `Earth.earthState` resolves `ERROR_NOT_AUTHORIZED` even with a fully correct session. **This blocks
  AR arrows on every device until it is provisioned** — see "Open questions".
- **Rich AR debug diagnostics** (section A1 of the brief): the overlay now shows which camera
  provider is active, a compact exact-stopping-point label (never a bare "Idle"), the reason
  preserved across a mid-session fallback to CameraX (previously destroyed by `stop()`'s own `Idle`
  emission), active anchor counts and arrow-pipeline sampled/visible counts — all four root-cause
  layers above were diagnosed *through this overlay*, confirming it does what section A1 asked.
- **6 new nearby-place categories**: Bank, ATM, Convenience (split out of Grocery), Medical,
  Entertainment, Shopping — 14 total, each mapped to real OSM tags (`PlaceCategoryMapper`), with
  matching marker colors on both the 2D map and the nearby cards.
- **A real "Explore what's around you"**: previously static text; now opens `NearbyExploreSheet,` a
  swipeable card carousel reusing the existing category-filter state and ranked places list (no
  parallel filter/list concept), with loading/empty/error copy and Details/Navigate actions reusing
  the existing place→destination flow. Confirmed working on-device (category chips, empty-state copy
  for a failed fetch).
- **Future sponsored-listing seam** (`Place.promotionType`): `Organic`/`Sponsored`, always `Organic`
  today, no ranking/payment/ads code — see ARCHITECTURE.md's Contextual places section.
- **Route Preview action block**: Start was a small, right-aligned `TextButton`, invisible until a
  route had already loaded. Now a filled, white-on-blue, ≥48dp button next to a secondary
  "Directions" (review, without discarding the computed route), inside the same card as the
  destination/ETA summary, visible (disabled) from the moment a destination exists. Confirmed
  working on-device, including the Start → Live handoff and Map↔Live↔Map continuity.
- 547 unit tests total (up from 529), 0 failures. `assembleDebug`, `testDebugUnitTest` and `lint`
  all pass clean (0 errors, 40 warnings — unchanged baseline, zero new lint issues).
- **On real hardware**: launch, location, category chips, Explore sheet, destination selection,
  Route Preview with the new action block, Start → Live, Map↔Live↔Map (×2), and the full AR
  diagnostic chain above were all exercised with no crash (`adb logcat` crash buffer and
  `FATAL EXCEPTION` both checked clean across the session). AR arrows themselves remain unobserved —
  blocked on Geospatial API provisioning (see above), not on anything exercised here.

### Phase 12A delta — Live-first polish, Place Details tabs, voice guidance

A follow-on spec landed mid-phase, integrated into the same working tree (no duplicate repository,
nearby-discovery, Start button or AR renderer):

- **"Explore Nearby Places"** replaces "Explore what's around you" — centered, no chevron, whole
  card tappable (unchanged behavior, copy/layout only).
- **Place Details now has tabs**: Overview (existing real fields), Reviews/Photos (honest "not
  available yet" state — no provider supplies either today; the tab structure exists so one can be
  plugged in later), Nearby (reuses `MapUiState.visiblePlaces`, no second fetch). Directions/Start
  are the two primary actions; Start starts navigation directly only when this exact place is
  already the `Ready` route, otherwise it behaves like Directions (`MapViewModel.onStartFromPlace`).
- **Live**: current speed and the posted limit now display independently (never one implying the
  other exists); arrival auto-returns to Map after a short confirmation window (manual button still
  works); the AR arrow sampler/anchor spacing was reviewed against the spec's maneuver-emphasis
  requirements and already satisfies them (real route-geometry tangent heading, distance-based
  scale, tightened spacing near maneuvers) — no changes needed there, only confirmed by code review
  (AR itself is still blocked on Geospatial API provisioning, so this could not be confirmed visually).
- **Voice guidance, new**: `core/voice` (provider-neutral `VoiceGuidanceEngine`, `VoiceEvent`,
  deterministic per-language templates, a staged-announcement planner) plus
  `voice/AndroidTextToSpeechEngine` (the one file touching `android.speech.tts`, mirroring
  `spatial/arcore`'s pattern). Five languages (English US/UK, Spanish, French, Nigerian Pidgin),
  always real templates, never a runtime translation call. Mute + language are both local
  `SharedPreferences` settings, no account. Voice is additive — nothing in `NavigationSession`/
  `RouteProgressTracker` reads from it, and a TTS failure never blocks navigation.
- A `<queries>` entry for `android.intent.action.TTS_SERVICE` was added proactively (API 30+
  package-visibility), since this phase had already found one real "works on the device but the app
  can't see it" bug (ARCore's Activity-resolution issue) and this is the exact same class of risk
  for TTS engine discovery.
- 579 unit tests total (up from 547), 0 failures. `assembleDebug`, `testDebugUnitTest` and `lint`
  all pass clean (0 errors, 43 warnings — unchanged baseline).
- **On real hardware**: app installs and runs with all of the above, no crash. TTS genuinely
  initializes — confirmed via logcat (`com.google.android.tts` loading real en-US voice model
  resources). One real device-specific finding: Samsung's bundled TTS engine (`SamsungTTS`)
  rejected the app's package (`"is not allowed [-4]"`); Android's TTS framework fell back to
  Google's TTS engine transparently, which is the expected, correct behavior for the
  engine-agnostic `TextToSpeech(context) { callback }` constructor this code uses — not a bug, but
  worth knowing about on Samsung devices specifically. The device's screen lock interrupted the
  session before the newest UI (Place Details tabs, Settings language picker, audible announcements)
  could be visually/audibly re-confirmed — see FIELD_TEST_REPORT.md.

## Next, not started (awaiting approval)

Tentative order; each needs its own scope decision.

1. **On-device validation of environment modes (Phase 11).** The highest-priority open item: no
   environment mode other than `Unknown` has been seen resolve against live map data. The classifier,
   hysteresis and every profile are unit-tested, but the Overpass road-class fetch and the resulting
   profile swap have never run against a real drive.
2. **Physical-device validation of Phases 7/8.** Every spatial claim about AR anchoring is still
   unverified against real ARCore hardware.
3. **Production basemap**: pick and host the tile source (PMTiles/Protomaps or self-hosted vector tiles), glyphs and sprites; flip `devOnly` to false; dark navy style to match the brand.
4. **Production geocoding**: move off the public Nominatim instance (self-hosted Nominatim/Photon or a contracted provider) before shipping.
5. **Production routing**: move off the public Valhalla demo instance (self-hosted Valhalla or a contracted provider) before shipping.
6. **Turn-by-turn guidance**: done in Phase 4; voice guidance is still open.
7. **Production places**: move off the public Overpass instance (self-hosted Overpass or a contracted places provider) before shipping — done in Phase 9, production provider still open.
8. **Later layers**: traffic, road hazards, weather, lane guidance, speed-limit intelligence — done in Phase 10, except a live traffic-congestion source and a wired lane-guidance provider (both still open).
9. **Production road-intelligence providers**: move off the public Overpass instance and Open-Meteo's free tier (same category of open question as tile/geocoding/routing/places) before shipping; separately, decide on a live traffic-congestion provider and whether to request Valhalla's `format=osrm` (or switch providers) to unlock lane guidance.

## Phase 11 — environment-dependent navigation (complete, on-device validation pending)

Answers "what kind of road am I on?" and lets every environment-sensitive consumer read the answer
without knowing how it was produced. Also makes the destination→route flow a deliberate two-step.

- **Classification is pure, stateless and deterministic** — `EnvironmentClassifier` over
  `EnvironmentSignals`. No ML, mirroring `PlaceRanking` and `RoadAlertPriority`. Thresholds live in an
  injectable `EnvironmentClassifierConfig`.
- **Signals are real and already available**: OSM `highway=*` road class via a new Overpass-backed
  `RoadClassRepository`, Phase 10's posted speed limit and current speed, mean maneuver spacing on the
  active route (`ManeuverSpacing`), and Phase 9's visible nearby-place count.
- **Highway comes from road classification, never from speed.** A driver crawling in motorway traffic
  is still on a motorway; a driver speeding on a rural road is not on one.
- **Hysteresis**: a candidate mode must hold for **3 consecutive ticks** before it replaces the
  current one — stricter than Phase 10's per-alert hysteresis, because a mode change flips arrow
  density, camera, POI priority and alert thresholds all at once. `reset()` on a new trip. Classification
  only runs while navigating.
- **One profile object** (`EnvironmentNavigationProfile`) is the single source of truth; no consumer
  branches on `DrivingEnvironment`. `EnvironmentProfiles.unknown` is pinned to the exact pre-Phase-11
  constants so an unresolvable mode behaves identically to Phases 1–10.
- **Profiles tune** arrow spacing/look-ahead/count, maneuver lead, POI limits and ranking weights,
  road-alert approach distances, speed emphasis, and map camera zoom/look-ahead.
- **Arrow integration is config-only** (`ARArrowPipeline.updateArrowConfig`): sampling is retuned,
  anchor lifecycle and placement-path selection are deliberately untouched and tested as such.
- **Two-step destination UX**: selecting a result opens Destination Details and does *not* request a
  route; `onDirectionsRequested` computes it into Route Preview; `startNavigation` begins the trip and
  flips to Live in one state update, with a reactive camera fallback back to Map that never blocks
  navigation and is disarmed by any manual mode choice.
- 529 unit tests total, 0 failures; all pre-existing Phase 1–10 tests preserved.
- **Emulator: the destination UX validated end to end** — Explore → search → results → Destination
  Details (no route, no Start) → Directions → Route Preview (route, arrival time, Start) → Start →
  Live opened automatically with camera and HUD. No crash at any point.
- **Emulator: Live → Map → Live continuity, arrival, and environment-mode transitions NOT completed.**
  The AVD degraded mid-session (ANRs in SystemUI, Phone and the Latin IME; restarted system services;
  transient PackageManager inconsistency) and could no longer deliver taps or text reliably. Those are
  unit-tested only.
- **On-device validation is the open item.** No mode other than `Unknown` has been observed resolving.

## Phase 12 — reliability + field testing (started, BLOCKED on hardware)

Goal is to prove the shipped journey on a real ARCore phone and on real roads, not to add features.
No application code was changed: no field defect was observed, and none could be, because no device
was available. Full record in [`FIELD_TEST_REPORT.md`](FIELD_TEST_REPORT.md).

- **Non-field validation passed on a clean tree at `0c1194a`.** `clean`, `assembleDebug`,
  `:app:testDebugUnitTest --rerun --no-build-cache` (529 tests, 0 failures) and `lint` (0 errors,
  40 dependency-freshness/hygiene warnings) all succeed. APK
  `app/build/outputs/apk/debug/app-debug.apk`, 60.72 MB, SHA-256
  `8FC4151E4B92C7BD8A30EF4D334BD9FE05D1907C4335617F14C277C2E8CB783C`.
- **BLOCKED: no physical device is connected** (`adb devices -l` empty, no Android/ADB USB device).
  Phase 12 explicitly cannot be completed from emulator-only testing, so the phase is marked
  incomplete. The `Pixel_8` AVD was deliberately not used to claim completion.
- **All 27 field areas are `NOT RUN`**, and are preserved as an executable checklist in the report:
  clean install, first-launch permissions, Explore/Map, search→directions→preview, Start→Live ×10,
  ARCore Earth tracking, arrow stability, road alignment, altitude/0.35 m lift, Map↔Live ×20,
  rerouting, arrival, GPS drift, environment modes, road intelligence, speed, places, network loss,
  background/foreground, screen lock, 30–60 min long session, battery, thermal, performance and
  failure recovery.
- **Two Windows build-workflow hazards recorded** (not app defects): `clean` and `lintReportDebug`
  both fail when a running Gradle daemon holds the lint-cache jars (`IOException` /
  `FileSystemException ... used by another process`). Run `.\gradlew.bat --stop` before `clean`.
- **Every prior on-device caveat carries forward unresolved** — AR anchoring, environment modes
  other than `Unknown`, live road-intelligence alert rendering, and the fixed 0.35 m arrow lift on
  real grades and overpasses.

## Phase 11 prerequisites

**Historical note:** this section originally read as a forward-looking plan when written at the end of
Phase 10. Phase 11 is now **done** — see the "Phase 11 — environment-dependent navigation" entry above.
Kept here, unedited otherwise, as the as-of-Phase-10 planning record (same convention as the
historical notes under Phase 10/7 prerequisites below).

Environment-dependent behavior (city/highway/suburb/rural modes — how Phase 10's road intelligence
should behave differently by environment, per item 9 above). Available at the start of Phase 11: the
full `core/roadintelligence` state (`RoadIntelligenceState`: events, traffic, weather, lane guidance,
speed limit, current speed, prioritized `activeAlerts`) that `MapViewModel` already fetches, matches
to the route, and stabilizes every tick — Phase 11 is a consumer of this state, not a builder of a
new one. `RoadAlertActivationConfig`'s per-type approach distances are already a single injectable
object, which is the natural seam for an environment-conditioned variant (e.g. a highway profile with
longer approach distances) rather than a parallel threshold system. Still needed: any actual
environment classification signal (OSM road class, speed, a geofence, or similar), and a decision on
whether environment conditioning lives in `RoadAlertActivationConfig` itself, a new layer above it, or
inside `MapViewModel`.

**What was actually built:** the classification signal is real OSM road class via a new
`RoadClassRepository`, not speed or a geofence; and the conditioning lives in **a new layer above**
`RoadAlertActivationConfig` (`EnvironmentNavigationProfile`, one profile object per mode), exactly as
that section anticipated — `RoadAlertActivationConfig` itself stayed a single fixed default, now
supplied *per profile* rather than being edited in place.

## Phase 10 prerequisites

**Historical note:** this section originally read as a forward-looking plan when written at the end
of Phase 9. Phase 10 is now **done** — see the "Phase 10: Road intelligence" entry above. Kept here,
unedited otherwise, as the as-of-Phase-9 planning record (same convention as the historical notes
under Phase 9/7 prerequisites below).

Traffic, road hazards, weather and lane guidance. Available now: the `core/places` pattern itself
(provider-neutral model, caching, corridor filtering, ranking) as a template for a second
contextual-data domain; `RouteCorridor` already generalizes over "geographic things near a route";
`MapEngine.setPlaces` and `LivePlaceProjector` are the Map/Live rendering precedents a hazard/traffic
layer would follow (a parallel `setHazards`-style seam, not a shared one — different data, different
priority rules). Still needed: any live traffic/incident/weather data source, lane-level geometry,
and a priority model that can rank a hazard against a maneuver, not just against another POI.

What was actually built: `setRoadEvents` (not `setHazards` — named for the provider-neutral
`RoadEvent` model) follows that exact precedent, as predicted. The "priority model that can rank a
hazard against a maneuver" turned out not to be needed in the form anticipated — Live's existing
maneuver-guidance priority slot was extended by one tier (safety alerts), rather than `RoadAlertPriority`
needing to rank against a maneuver directly. Traffic/incident data sources and lane-level geometry
remain exactly as open as predicted here — see "Providers" under Road intelligence (Phase 10) in
ARCHITECTURE.md for why, and Phase 11 prerequisites above for what that leaves open.

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

- **Blocking AR arrows entirely as of Phase 12A**: no Google Cloud project has the ARCore Geospatial
  API enabled with this app's package name (`com.openmaps.app`) and signing certificate SHA-1
  (debug **and** release) registered, so `Earth.earthState` resolves `ERROR_NOT_AUTHORIZED` even
  with a fully correct ARCore session (confirmed on a physical device). This is account-level
  provisioning, not something fixable in application code.
  - **Now documented and, on the app side, already wired.** `AndroidManifest.xml` declares the
    `com.google.android.ar.API_KEY` meta-data that ARCore reads, and `app/build.gradle.kts` injects
    the value from `-PARCORE_API_KEY` / `local.properties` / the `ARCORE_API_KEY` environment
    variable, defaulting to empty so credential-less builds still compile and fall back to CameraX.
  - **Remaining action is one human step in Google Cloud**: create/select a project, enable the
    **ARCore API**, create an API key, and restrict it to *Android apps* → package
    `com.openmaps.app` + this machine's debug certificate SHA-1
    (`F2:D2:9F:2D:FE:0A:0A:90:80:58:3E:EC:3B:BB:5E:C2:9E:A9:A1:40`). A release build needs a second
    key restricted to the release certificate.
  - Full step-by-step, including how to read the fingerprints off any machine and how to verify the
    key reached the APK: **SETUP.md → "ARCore Geospatial provisioning (Google Cloud)"**.
  - Still open and *not* fixed by the key: Google's requirement that Geospatial apps show the user a
    privacy/data-use prompt. OpenMaps has no such prompt yet — a release blocker, not a test blocker.
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
