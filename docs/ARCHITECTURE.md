# OpenMaps Architecture

OpenMaps is a United States-only, Android-first navigation app. This document describes what exists after **Phase 10 (road intelligence: hazards, weather, speed limits, prioritized alerts)**, built on Phase 9 (contextual places) and Phase 8 (AR route arrows on ARCore world anchors, itself built on a repaired Phase 7 geospatial anchoring). Computer vision, AI and authentication do not exist yet.

> **Verification status.** Phases 1-5 were built, unit-tested and manually validated end-to-end on a Pixel 8 API 35 emulator. **Phase 6 is built, unit-tested (175 tests) and emulator-validated only on its fallback paths** — the test emulator has no ARCore and no AR hardware, so Earth tracking, geospatial pose and heading are **unverified on a physical device**. See [SETUP.md](SETUP.md#verification-checklist).

## Stack

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3 |
| Build | Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), AGP 8.7.3 |
| Map renderer | MapLibre Native Android `org.maplibre.gl:android-sdk:11.13.5` |
| Camera | CameraX `androidx.camera` 1.4.2 (preview use case) — the Live fallback camera |
| AR / spatial | ARCore `com.google.ar:core` 1.56.0, Geospatial (Earth) mode, runtime support-checked, optional |
| Map data | OpenStreetMap-derived vector tiles via a configurable style URL |
| Geocoding | OSM Nominatim (`nominatim.openstreetmap.org`), development-only — see [Geocoding](#geocoding) |
| Routing | Valhalla demo instance (`valhalla1.openstreetmap.de`), development-only — see [Routing](#routing) |
| Road hazards / speed limits | Overpass API, development-only — see [Road intelligence (Phase 10)](#road-intelligence-phase-10) |
| Weather | Open-Meteo (`api.open-meteo.com`), free, no API key, development-only — see [Road intelligence (Phase 10)](#road-intelligence-phase-10) |
| Location | Platform `LocationManager` (no Google Play services) |
| State | `ViewModel` + `StateFlow` |
| DI | Manual `AppContainer` (no framework) |
| SDK levels | minSdk 26, targetSdk/compileSdk 35 |

Explicitly **not** used: Google Maps SDK, Mapbox Maps SDK, Google Places API, Google Directions API, proprietary navigation SDKs.

## Layers and packages

```
com.openmaps.app
├── OpenMapsApplication / AppContainer   manual DI: LocationRepository, MapStyleProvider, prefs
├── MainActivity                         splash theme swap, edge-to-edge, hosts Compose
├── OpenMapsApp                          splash -> persistent shell (map + overlays)
├── core
│   ├── designsystem                     colors, theme, typography, glass modifier, brand mark
│   ├── location                         Phase 0 location layer
│   │   ├── LocationModels               UserLocation, LocationPermissionState, LocationStatus   (pure Kotlin)
│   │   ├── LocationRepository           interface + LocationUpdate                              (pure Kotlin)
│   │   ├── AndroidLocationRepository    LocationManager implementation
│   │   └── LocationPermissions          OS permission -> LocationPermissionState (+ "already asked" flag)
│   ├── geocoding                        Phase 2 geocoding layer
│   │   ├── GeocodingModels              SearchResult, GeocodingException                        (pure Kotlin)
│   │   ├── GeocodingRepository          interface                                                (pure Kotlin)
│   │   ├── NominatimGeocodingRepository HTTP + JSON implementation (java.net + org.json)
│   │   ├── NominatimResponseParser      pure response-parsing logic, unit-tested without network
│   │   ├── RecentSearchList             pure dedup/cap policy for recents                         (pure Kotlin)
│   │   ├── RecentSearchStore            interface
│   │   └── SharedPreferencesRecentSearchStore  SharedPreferences-backed implementation
│   ├── routing                          Phase 3 routing layer
│   │   ├── RoutingModels                Coordinate, RouteResult, RouteBounds, RoutingException   (pure Kotlin)
│   │   ├── ManeuverModels               ManeuverType, RouteManeuver (Phase 4)                    (pure Kotlin)
│   │   ├── RoutingRepository            interface                                                (pure Kotlin)
│   │   ├── ValhallaRoutingRepository    HTTP + JSON implementation (java.net + org.json)
│   │   ├── ValhallaResponseParser       pure response-parsing logic, unit-tested without network
│   │   └── PolylineDecoder              pure polyline6 decoder, unit-tested against a captured response
│   ├── navigation                       Phase 4 turn-by-turn layer                                (pure Kotlin)
│   │   ├── RouteProgress                progress snapshot                                        (pure Kotlin)
│   │   ├── RouteProgressTracker         snaps a location onto route geometry
│   │   ├── OffRouteDetector             multi-sample hysteresis before a reroute
│   │   ├── NavigationCamera             follow-camera target (ahead on the route, bearing)
│   │   └── NavigationSession            per-trip state: tracker + detector + camera
│   ├── camera                           Phase 5 camera permission model
│   │   ├── CameraModels                 CameraPermissionState                                    (pure Kotlin)
│   │   └── CameraPermissions            OS permission -> CameraPermissionState (+ "already asked" flag)
│   ├── roadintelligence                  Phase 10 road intelligence (hazards, weather, speed limits, alerts) — see below
│   └── spatial                          Phase 6 AR localization
│       ├── SpatialModels                SpatialLocalizationState, SpatialPose, quality, availability (pure Kotlin)
│       ├── SpatialLocalizationEngine    interface the app talks to                              (pure Kotlin)
│       ├── LocalizationQualityMapper    accuracy -> LocalizationQuality thresholds               (pure Kotlin)
│       ├── LiveLocalizationStatus        SpatialLocalizationState -> what the HUD shows           (pure Kotlin)
│       ├── GeoProjector                 geographic -> screen projection seam                     (pure Kotlin)
│       ├── NoopSpatialLocalization      the non-AR fallback engine
│       └── arcore/ArCoreSpatialLocalization   the ONLY file importing com.google.ar.core
├── map                                  the ONLY package that knows about MapLibre
│   ├── MapModels                        CameraState, MapCommand, MapEvent, MapMode, MapError    (pure Kotlin)
│   ├── MapStyleProvider                 MapStyleSource + provider interface                     (pure Kotlin)
│   ├── BuildConfigMapStyleProvider      style from Gradle properties
│   ├── MapEngine                        interface the app talks to
│   ├── MapLibreMapEngine                MapLibre implementation
│   └── MapLibreHost                     Compose host (AndroidView + lifecycle + flow wiring)
├── camera                               the ONLY package that knows about CameraX
│   ├── CameraModels                     RearCameraEngine, RearCameraState, CameraFailure          (pure Kotlin)
│   ├── CameraXRearEngine                CameraX implementation (preview use case only)
│   └── RearCameraHost                   Compose host (AndroidView + lifecycle)
└── feature
    ├── map       MapViewModel, MapUiState, RouteState, NavigationState, MapScreen, MapControls, NavigationControls
    ├── live      LiveViewModel, LiveUiState, LiveHud (pure NavigationState -> HUD mapping), LiveScreen
    ├── search    SearchViewModel, SearchUiState, SearchScreen (real geocoding search)
    ├── settings  SettingsScreen
    └── splash    SplashScreen
```

Dependency direction: `feature -> map (interface + models), camera (interface + models), core`. `MapViewModel`, `MapUiState`, `SearchViewModel`, `SearchUiState`, `LiveViewModel` and `LiveUiState` import **no** Android framework and **no** MapLibre or CameraX types, which is why they can be unit-tested on the JVM. `core.geocoding.SearchResult` and `core.routing.RouteResult` are used directly as `MapEngine.setDestination`/`setRoute`'s parameter types, exactly mirroring how `core.location.UserLocation` is already used for `setUserLocation` — `map` depending on `core.*` pure-Kotlin models is an established pattern, not a new exception.

## MapLibre integration

`MapEngine` is the seam. The app depends on the interface and on engine-neutral types; `MapLibreMapEngine` translates them.

- **Commands in** (`MapCommand`): `MoveCamera(target, animated, durationMillis)`, `RetryStyle`, `FitBounds(bounds, paddingPx, durationMillis)` (Phase 3).
- **Events out** (`MapEvent`): `StyleLoaded`, `StyleFailed`, `UserGestureStarted`, `BearingChanged`, `CameraIdle`.
- **Location in**: `setUserLocation(UserLocation?)`.
- **Destination in** (Phase 2): `setDestination(SearchResult?)` — shows/hides/replaces the destination marker.
- **Route in** (Phase 3): `setRoute(RouteResult?)` — shows/hides/replaces the route line.
- **Insets in**: `setBottomInset(px)` keeps the required map attribution above the bottom panel.

Planned extension points, deliberately not declared yet: AR/map synchronisation (expose camera and projection) — now the live Phase 6 requirement. Active-navigation camera tracking shipped in Phase 4.

## Live camera mode (Phase 5)

### CameraX architecture

`camera.RearCameraEngine` is the seam, and is deliberately the same shape as `map.MapEngine`: the app depends on the interface and on engine-neutral types, and only `CameraXRearEngine` imports CameraX.

- **In**: `createView(Context)` (once), `bind(LifecycleOwner)`, `release()`.
- **Out**: `events: Flow<CameraEngineEvent>` carrying a `RearCameraState` — `Idle | Starting | Ready | Unavailable(CameraFailure)`.
- **`CameraFailure`**: `NoRearCamera | BindingFailed | InUse | Unknown`. Every CameraX throw is classified into one of these; **no exception ever reaches the UI**, and no CameraX type appears above this package.

**Preview only.** There is deliberately no `ImageAnalysis` use case: Phase 6 adds AR localisation, and attaching an analyzer now would push every preview frame through a pipeline with nothing consuming it.

Two binding decisions worth knowing:

- **Binding is lifecycle-owner driven**, not started/stopped by hand. CameraX closes the camera when the owner stops and reopens it on start, so backgrounding cannot leave the camera held. Verified on-device: home/foreground cycles keep the same PID and a live preview.
- **`release()` is explicit and idempotent**, called from `RearCameraHost`'s `DisposableEffect`. That is what makes repeated Map -> Live -> Map cycles safe — each departure unbinds, and the next `bind` rebinds cleanly. Confirmed with `dumpsys media.camera`: no client holds the camera once Live is closed.

`PreviewView` uses `FILL_CENTER` (fills and crops, keeping the road ahead visible under a full-bleed HUD; `FIT_CENTER` would letterbox and shrink it) and `ImplementationMode.COMPATIBLE` (TextureView) so the preview composites cleanly with Compose overlays. CameraX rotates the feed to the display rotation, so portrait needs no manual matrix work.

### Live screen ownership

**Live does not own navigation.** There is one `MapViewModel` and one `MapUiState.navigationState`; `AppShell` derives the HUD from it and passes it down:

```
MapUiState.navigationState + selectedDestination.name
        └─► LiveHud.state(...)  ──►  LiveHudState  ──►  LiveScreen renders it
```

`LiveViewModel` holds only **camera permission and camera health**. Nothing on the Live path starts, stops, copies or mutates a trip — which is precisely why Map <-> Live preserves navigation with no extra bookkeeping, and why switching modes never restarts routing. A second navigation session owned by Live would have been the easiest way to break that.

`LiveHud.state` is a **pure function**, which is what makes Phase 5 testable without a camera: it is the entire guidance-logic surface, unit-tested against `NavigationState` alone, and the HUD composable depends on this one small derived value rather than the whole UI state (so a location tick does not recompose the camera preview).

One correctness note carried forward from Phase 4 and fixed here: the primary HUD line pairs the **current** maneuver with `distanceToNextManeuverMeters`. That distance is measured to `currentManeuver.endShapeIndex`, and a Valhalla maneuver's instruction is the action performed at the end of its own segment, so pairing the *next* maneuver with it (as the Map banner did) mislabelled every turn. The following maneuver is the secondary "Then …" line. Both surfaces now agree.

### Camera permission flow

```
Live opened ──► CameraPermissions.evaluate() ──► LiveViewModel.onCameraPermissionChanged()
                                                              │
                    NotRequested/Denied ──► RequestCameraPermission ──► OS dialog ──┐
                    PermanentlyDenied ────► OpenAppSettings                            │
                    Granted ─────────────► CameraXRearEngine.bind()  ◄────────────────┘
```

Modelled exactly like `LocationPermissionState` (`Unknown | NotRequested | Granted | Denied | PermanentlyDenied`), including the same workaround: the OS cannot distinguish "never asked" from "don't ask again", so a persisted `camera_permission_requested` flag combines with `shouldShowRequestPermissionRationale`. Camera uses its own prefs key and its own launcher, and is **only requested when Live is actually opened** — never on launch.

Re-evaluated on `ON_RESUME`, so a user who changed it in Settings is picked up.

**The camera gate is checked before the navigation HUD, not inside it.** An earlier revision only showed the permission panel when `hudState == Idle`, which meant an *active* trip rendered turn-by-turn text over a black screen with no way to grant the camera. Guidance is only actionable when the road is visible, so `!isCameraUsable` now short-circuits the whole HUD and shows the permission/failure panel with a retry and a way back to the map.

### Touch handling

Live is layered over an always-composed Map, and the MapLibre view underneath is a **native view**, which receives touches regardless of Compose z-order. A screen-sized `pointerInput` sink on the Live *container* therefore blocked Live's own buttons as well (they never became clickable). The sink now lives on a transparent layer placed **above the preview but below every control**, so HUD buttons win the hit test while stray gestures cannot reach the map.

### HUD structure

- **Top bar** — wordmark, a "Navigating" status pill when a trip is active, an X that returns to the map, and the Map/Live toggle. A direct close control sits next to the toggle so returning is always one tap.
- **Maneuver row** — arrow (rotated by `ManeuverType`), "in 382 ft", the current instruction, and the following maneuver as a secondary line.
- **Bottom summary** — destination name plus remaining distance and time, straight from `RouteProgress` (never recomputed).
- **Transient states** — "Rerouting…" and "Off route" replace the maneuver row; the bottom summary is suppressed while off route, because those figures are stale.
- **Arrival** — its own card with a single "Back to map" action. Arrival is a resting state, not an error: the camera stays stable and the app does not close.

Readability over a live feed is the constraint that shapes it all: text sits on a **directional scrim** (strongest behind the text, fading out) rather than blurred glass. No panel uses backdrop blur — it reads as generic glassmorphism and costs real GPU time every frame.

### Destination marker

`MapLibreMapEngine.setDestination` renders the marker as a `GeoJsonSource` + `CircleLayer` (the "POI overlays -> symbol layers" extension point this doc previously flagged, implemented as circles rather than symbols — see below). `GeoJsonSource.setGeoJson` always replaces the previous feature wholesale, so there is never more than one marker; passing `null` clears it. The marker is brand electric blue (`#2F7BFF`) with a white stroke, visually distinct from the pulsing `LocationComponent` current-location dot.

**Known limitation**: a `SymbolLayer` with a programmatically added custom pin icon (tried both via `Style.addImage(String, Drawable)` and a manually rasterized `Bitmap`, with `icon-size`/`icon-anchor`/`icon-allow-overlap` explicitly set and the bitmap's pixel content verified non-transparent) consistently rendered nothing on this project's test emulator (MapLibre 11.13.5, Pixel 8 API 35, 16 KB page size, OpenGL backend) — no exceptions, all layer/property/visibility state read back correctly, simply no pixels drawn. A `CircleLayer` with the identical source and camera position renders correctly, which is why the marker is a circle and not a custom pin. Revisit a custom icon (and the deleted `ic_destination_pin.xml` sketch, recoverable from git history if wanted) if a future MapLibre release or a physical device shows different behavior.

### Route line

`MapLibreMapEngine.setRoute` renders the route the same way as the destination marker — a dedicated `GeoJsonSource` (`openmaps-route-source`) + a dedicated `LineLayer` (`openmaps-route-layer`), brand electric blue, round cap/join, 5px wide. `setGeoJson` replaces the previous line wholesale, so there is never more than one route line, and passing `null` (or an empty geometry) clears it. The route layer is added with `Style.addLayerBelow(..., DESTINATION_LAYER_ID)` so the destination marker always renders on top of the line leading to it; by construction the destination layer already exists by the time a route layer is first created (`loadStyle`'s style-loaded callback calls `syncDestination()` before `syncRoute()`), so this ordering is reliable rather than racy. A plain data layer like this cannot intercept touches, so it does not interfere with map gestures.

Camera fit: on a successful route, `MapViewModel` sends one `MapCommand.FitBounds` built from the route's bounds (computed server-side by Valhalla from the actual route geometry, not derived client-side). `MapLibreMapEngine` converts it to a MapLibre `LatLngBounds` (`LatLngBounds.from(north, east, south, west)` — note the parameter order) and calls `animateCamera(newLatLngBounds(bounds, paddingPx), durationMillis)`. This fires exactly once per successful route (inside the coroutine's `onSuccess`), never on a timer or per-frame, so it never fights a camera move the user makes afterward — there is no active-navigation camera tracking yet (Phase 4+).

### Lifecycle and view ownership

- One engine owns one `MapView`. Compose creates the engine with `remember` and the view in `AndroidView(factory = ...)`; there is no `update` block, so ordinary state changes never touch the view.
- `MapLibreHost` registers a `LifecycleEventObserver`. Lifecycle replay brings the view up to the current state; `MapLibreMapEngine` maps events to `MapView.onCreate/onStart/onResume/onPause/onStop/onDestroy` with a small stage guard so calls are never duplicated or out of order, and `release()` walks the view down if the composable leaves while the activity lives.
- `onLowMemory` is forwarded through `ComponentCallbacks2`.
- **Search, Settings and Live are layered over an always-composed Map** rather than being navigation destinations. A navigation library would dispose the map composition on every screen change and force a style reload. Adopt one when the screen count justifies it and the map can be hoisted.
- While Live is showing, the map keeps running underneath (it is an overlay, not a destination). The camera is released on the way out; the map is intentionally left running so returning is instant. Pausing the map while Live is foregrounded is a possible battery optimisation, not yet done.

## Map style configuration

`MapStyleProvider.resolve()` returns a `MapStyleSource`:

- `Url(url, name, isDevelopmentOnly, attribution)`: a style JSON over HTTPS.
- `InlineJson(json, name, isDevelopmentOnly, attribution)`: a style document built at runtime or bundled; this is the hook for self-hosted/PMTiles-backed styles.

`BuildConfigMapStyleProvider` reads three `BuildConfig` fields generated in `app/build.gradle.kts`:

| Gradle property | Default | Meaning |
| --- | --- | --- |
| `openmaps.style.url` | `https://tiles.openfreemap.org/styles/liberty` | style JSON URL |
| `openmaps.style.name` | `OpenFreeMap Liberty` | shown in Settings |
| `openmaps.style.devOnly` | `true` | shows a "Development only" label |

**The default is DEVELOPMENT ONLY.** OpenFreeMap is a free public instance (OpenStreetMap data, no API key). It has no SLA, and we have not confirmed its terms for our expected production volume. Do not ship to the public on it; move to a self-hosted or contracted source first (see [PMTiles migration](#migrating-to-pmtiles--self-hosted-tiles)). We intentionally do **not** use `tile.openstreetmap.org`, whose usage policy forbids this kind of app traffic.

Attribution is mandatory for OSM-derived data. The SDK's attribution control stays enabled, and Settings shows `MapStyleSource.attribution`.

## Geocoding

`GeocodingRepository.search(query): Result<List<SearchResult>>` is the seam (`core/geocoding`), same shape as `LocationRepository`: an interface the app depends on, with the provider-specific implementation swappable behind it. `Result.success(emptyList())` means "no matches," never a failure; failures come back as `Result.failure` carrying a typed `GeocodingException` (`NoNetwork`, `Timeout`, `Server(code)`, `RateLimited`, `Malformed`).

**Provider**: `NominatimGeocodingRepository` calls the public OSM Nominatim instance (`nominatim.openstreetmap.org`) — free, open, no API key, the same OSM-data lineage as the OpenFreeMap tile source. Requests are HTTPS GET with a descriptive `User-Agent` (required by Nominatim's usage policy), an 8s connect/read timeout, and `countrycodes=us` (matching OpenMaps' documented US-only scope). Results are capped at 10.

**Development-only constraints** (same category as the tile source — see [Map style configuration](#map-style-configuration)): Nominatim's usage policy (https://operations.osmfoundation.org/policies/nominatim/) caps a single IP at roughly 1 request/second server-side, forbids bulk/heavy use, and has no SLA. **Do not ship to the public on it.** Before production, move to a self-hosted Nominatim/Photon instance or a contracted geocoding provider — the `GeocodingRepository` interface is the only thing that needs a new implementation; nothing in `SearchViewModel` or the UI changes.

**Parsing** is split from the network call: `NominatimResponseParser.parse(body): List<SearchResult>` is a pure function (only `org.json`, no Android types), so it is unit-testable without a real HTTP request. `NominatimGeocodingRepository.search` does the HTTP round trip, then delegates to the parser, mapping any failure (timeout, non-2xx, malformed JSON, cancellation) to the right `GeocodingException` — `CancellationException` is explicitly rethrown rather than swallowed, so a debounced search that gets superseded cancels cleanly instead of surfacing a spurious error.

**Recent searches**: `RecentSearchStore` (interface) / `SharedPreferencesRecentSearchStore` (impl) persists up to 8 entries as a small JSON array in the same `SharedPreferences` file as `LocationPermissionPrefs` — no database for one short list. Ordering/dedup/cap is `RecentSearchList.withRecorded`, a pure function (most-recent-first, re-selecting an existing entry moves it to the front instead of duplicating it) kept separate so it is unit-testable without `SharedPreferences`.

## Routing

`RoutingRepository.route(origin, destination): Result<RouteResult>` is the seam (`core/routing`), the same shape as `GeocodingRepository`: a provider-neutral interface, `Result.failure` carrying a typed `RoutingException` (`NoNetwork`, `Timeout`, `Server(code)`, `Malformed`, `NoRouteFound`) instead of a thrown exception. `RouteResult` (`geometry: List<Coordinate>`, `distanceMeters`, `durationSeconds`, `bounds: RouteBounds?`) is the only routing-provider shape anything outside `core/routing` ever sees — no Valhalla JSON reaches `MapViewModel` or the UI.

**Provider**: `ValhallaRoutingRepository` calls the public Valhalla demo instance (`valhalla1.openstreetmap.de`), run by FOSSGIS e.V. on OpenStreetMap data — free, open, no API key, the same lineage as the tile source and Nominatim. It POSTs `{"locations":[{lat,lon},{lat,lon}],"costing":"auto","units":"kilometers"}` with an 10s connect/read timeout and a descriptive `User-Agent`. `costing` is hardcoded to `"auto"` (driving) — Phase 3 has no mode picker.

**Development-only constraints** (same category as the tile source and the geocoding provider): this is a shared community demo server with no SLA, no documented rate limit, and (observed, not just assumed — see below) a practical limit on how far apart the two points can be. **Do not ship to the public on it.** Before production, move to a self-hosted Valhalla instance or a contracted routing provider — only `RoutingRepository`'s implementation needs to change.

**Parsing** mirrors geocoding: `ValhallaResponseParser.parse(body): RouteResult` is a pure function (only `org.json`), unit-tested without a real HTTP request, including against a response body captured from a live call. It reads `trip.summary.length` (kilometers, since the request asked for `units: kilometers`, converted to meters) and `trip.summary.time` (seconds, always unit-independent), concatenates `shape` across all of `trip.legs` (normally one leg for a two-point request) through `PolylineDecoder`, and reads `min_lat`/`min_lon`/`max_lat`/`max_lon` off `trip.summary` for `bounds` — Valhalla computes these server-side from the actual route, so nothing is recomputed client-side. `trip.status != 0` throws `RoutingException.NoRouteFound`.

**The "no route" response shape is different from a parse failure**, and is handled before the parser ever sees it: an unroutable pair (confirmed live, e.g. two mid-ocean points) comes back as **HTTP 400** with a body like `{"error_code":171,"error":"No suitable edges near location","status_code":400}` — there is no `"trip"` key at all. `ValhallaRoutingRepository` checks for this shape directly on any 4xx response (parseable JSON with an `"error"` field) and maps it to `RoutingException.NoRouteFound` rather than `Malformed`. This is also what happens in practice for routes the demo server's distance limits reject (observed: a cross-country US route came back `NoRouteFound`; a request a few miles away succeeded normally — see the Phase 3 emulator validation notes).

**Polyline decoding**: Valhalla's `shape` is a Google-style encoded polyline, but at **precision 6**, not the more common precision 5 — confirmed empirically (decoding a live response's shape at precision 5 produced an impossible ~407° latitude; precision 6 landed within meters of the requested origin). `PolylineDecoder` is pure Kotlin, independent of any MapLibre/geojson type, and is unit-tested both against a hand-verified minimal case and the captured live shape.

## Route flow

```
MapUiState.selectedDestination set (Phase 2 flow) ──► MapViewModel.requestRoute()
                                                          │
          currentLocation == null ? ── yes ──► routeState = WaitingForLocation (no request made)
                                      ── no  ──► routeState = Loading
                                                   │
                                                   ▼
                                     RoutingRepository.route(origin, destination)
                                          │                           │
                                       success                     failure
                                          ▼                           ▼
                           routeState = Ready(RouteResult)   routeState = Error(RouteError)
                                          │
                                          ▼
                       one-time MapCommand.FitBounds(route.bounds)
```

A location fix that arrives while `routeState == WaitingForLocation` re-triggers `requestRoute()` automatically (see `MapViewModel.onLocationUpdate`'s `Fix` branch) — nothing invents a default origin, and the user never has to retry by hand. `requestRoute()` always cancels any in-flight `routeJob` first, so a destination change (or any other reason to call it again) can only ever let the *latest* call's result land; `onDestinationCleared()` also cancels the job and resets `routeState` to `Idle`. `MapScreen`'s `routeFlow` derives `(routeState as? RouteState.Ready)?.route` and feeds it to `MapLibreHost` -> `engine.setRoute`, the same declarative pattern Phase 2 established for the destination marker — the route line is never driven by an imperative "clear" call, it simply disappears when `routeState` stops being `Ready` for any reason (cleared, replaced, erred).

**Why `RouteState` is one field, not five**: `MapUiState.routeState: RouteState` is a sealed type (`Idle | WaitingForLocation | Loading | Ready(RouteResult) | Error(RouteError)`) rather than the flatter `routeStatus`/`routeGeometry`/`routeDistanceMeters`/`routeDurationSeconds`/`routeError` shape sketched during planning. A `Ready` state structurally cannot exist without its `RouteResult`, and an `Error` cannot exist without its reason — the flatter shape would allow nonsensical combinations (e.g. `routeStatus = Ready` with `routeDistanceMeters = null`) that the type system should rule out instead of leaving to runtime discipline.

## Location flow

```
Activity resume ──► LocationPermissions.evaluate() ──► MapViewModel.onPermissionStateChanged()
                                                          │
 ON_START/ON_STOP ──► onAppVisibilityChanged() ───────────┤
                                                          ▼
                        granted && visible ?  ── yes ──► LocationRepository.updates()  (collected)
                                              ── no  ──► stop collecting (battery/privacy)
```

Permission is modelled as `Unknown | NotRequested | Granted(precise) | Denied | PermanentlyDenied`. The OS cannot distinguish "never asked" from "don't ask again", so a persisted `location_permission_requested` flag combines with `shouldShowRequestPermissionRationale`. Fine and coarse are requested together; approximate-only grants work (network provider).

`AndroidLocationRepository.updates()` is a cold `callbackFlow` that: reports whether device location is on and tracks changes (broadcast receiver), emits a recent last-known fix (<= 2 min old) for a fast first paint, and streams GPS (precise permission) and network fixes. Providers are re-registered when the enabled set changes. It never throws for missing permission or provider; it emits `LocationUpdate.Failure`.

`MapViewModel` turns that into `LocationStatus` (`Idle | Loading | Available | ServicesDisabled | Unavailable`). If no fix arrives within 20 s it becomes `Unavailable`. Each situation maps to a `LocationPrompt` the UI renders as one compact card with the right primary action:

| Situation | Card | Action |
| --- | --- | --- |
| Not requested | Show your location | system permission dialog |
| Denied | Location access is off | dialog again |
| Permanently denied | Location is blocked | open app settings |
| Granted, GPS off | Location is turned off | open location settings |
| Granted, no fix | Can't find your location | restart collection |
| Granted, waiting | Finding your location... | none |

A location failure never blocks the map. A card can be dismissed and returns only if the situation changes.

## Camera flow

State of record is the `MapViewModel`; the engine is the renderer.

1. **Initial camera**: the ViewModel's last known camera (a configuration change restores it). Before any fix this is the contiguous-US overview (`CameraState.UnitedStates`, the geographic centre of the lower 48; not a personal address).
2. **First fix**: centre once at zoom 15 (animated, 1.4 s), unless the user already touched the map (`UserGestureStarted`). After that decision it is never made again.
3. **Later fixes**: move only the blue dot. The camera is never force-recentred.
4. **Recenter**: animates to the user, keeping the current bearing and pitch and at least zoom 14. With no fix it runs the recovery action instead (permission, settings, retry).
5. **Compass**: shown only when the bearing is more than 1 degree from north; tap animates bearing to 0 and keeps zoom/pitch.
6. **Persistence**: the camera is stored on `CameraIdle`, and optimistically when the app issues a move, so rotating the device mid-animation restores the destination.
7. **Route fit** (Phase 3): a successful route fires one `MapCommand.FitBounds` so the whole route is visible. Fired once, from the `onSuccess` of the route request — never repeated, never fights a camera move the user makes afterward. No active-navigation camera tracking yet.

Gestures (pan, pinch zoom, double-tap, two-finger rotate, two-finger pitch) are MapLibre's built-in gestures with zoom clamped to 2-20. The built-in compass and logo are disabled; OpenMaps draws its own compass.

The blue dot is MapLibre's `LocationComponent` with its own location engine disabled and `CameraMode.NONE`; we feed it from our location layer through `forceLocationUpdate`, so there is one source of location truth.

## Search and destination flow

```
Map (tap search bar)
  → AppShell sets overlay = Search, composes SearchScreen(searchViewModel, ...)
  → user types → SearchViewModel.onQueryChanged (debounced, min length, cancels stale jobs)
  → GeocodingRepository.search → SearchUiState.results / isSearching / error
  → user taps a result or a recent
      → SearchScreen calls searchViewModel.onResultSelected(result)   (records recent, resets query)
      → SearchScreen's onResultSelected callback (from AppShell) also fires:
          → MapViewModel.onDestinationSelected(result)  (sets selectedDestination, flies camera, requests a route — see Route flow)
          → overlay = None
  → MapScreen's destinationFlow (uiState.map { it.selectedDestination }) → MapLibreHost → engine.setDestination
  → BottomPanel shows the destination summary (+ Phase 3: distance/ETA, see Route flow) with a clear (×) button → onDestinationCleared()
```

Two ViewModels, two responsibilities, deliberately not merged: `SearchViewModel` owns the *search session* (query, results, loading/error, recent list) and is constructed the same way as `MapViewModel` (`viewModel(factory = searchViewModelFactory(container))` in `AppShell`, so it persists across the search overlay opening/closing — a reopened search screen keeps whatever was mid-flight). `MapViewModel` owns *what the map shows* (camera, current location, destination marker, route) and gains only `onDestinationSelected`/`onDestinationCleared` for Phase 2 (plus the private `requestRoute` for Phase 3); it has no knowledge of search queries or either provider. The callback wiring (`onResultSelected` bubbling from `SearchScreen` up through `AppShell`) is the same plain-lambda pattern `onBack`/`onSearchClick`/`onSettingsClick` already used in Phase 1 — no new navigation/event-bus mechanism was introduced.

## State model

`MapUiState` holds `currentLocation`, `locationPermission`, `locationStatus`, `cameraState`, `selectedMapMode`, `isMapReady`, `error`, `dismissedPrompt`, (Phase 2) `selectedDestination` and (Phase 3) `routeState`; `isLocationLoading` and `locationPrompt` are derived. One-shot effects that need an Activity (`RequestLocationPermission`, `OpenAppSettings`, `OpenLocationSettings`) are `MapUiEvent`s on a buffered channel.

`SearchUiState` (Phase 2) holds `query`, `results`, `isSearching`, `error` (`SearchError`, a UI-facing enum distinct from `GeocodingException`) and `recentSearches`; `hasNoResults` is derived.

`RouteState` (Phase 3, see [Route flow](#route-flow)) is `MapUiState.routeState`'s type: `Idle | WaitingForLocation | Loading | Ready(RouteResult) | Error(RouteError)`.

The live bearing during a rotate gesture is exposed as a separate `compassBearing: StateFlow<Float>` collected only by the compass button, so rotating the map does not recompose the screen every frame.

## Performance notes

- The MapLibre view is created once per screen composition and never recreated by state changes.
- `MapLibreHost` receives flows, not state; user-location updates are `distinctUntilChanged`.
- Camera-move events are throttled to bearing changes of at least 0.5 degrees.
- The engine uses MapLibre's default `SurfaceView`. If overlay z-ordering or animation artefacts appear over the map on a device, try `textureMode(true)` (slower but composites like a normal view).

## Spatial localization (Phase 6)

### Camera integration decision — ARCore replaces CameraX, it does not run beside it

**The Android camera stack allows exactly one owner of the rear camera.** ARCore's tracking pipeline
needs those frames itself (motion tracking, VPS), so a CameraX preview bound at the same time would
either starve ARCore or be starved by it, nondeterministically. Running both was therefore rejected.

ARCore `SharedCamera` was also rejected: it exists to hand ARCore-owned frames *to* another pipeline,
and Phase 6 has no second pipeline. Adding it would be complexity with no consumer.

Because ARCore is *also* the source of the camera image once `setCameraTextureName` is called, **one
owner can serve both jobs**: `ArCoreSpatialLocalization` renders the camera background itself through
a minimal `GLSurfaceView`, so it reports `providesPreview = true`. Live composes that surface
*instead of* the CameraX preview — never both.

**The provider decision is state, not a type check.** An early revision chose the provider from the
engine's `providesPreview` capability, which meant that whenever AR was selected and then failed, the
CameraX fallback had never been composed and the user got a black screen with no camera at all
(observed on the emulator). The decision is now made in two steps:

1. `probeAvailability()` — a **camera-free** `ArCoreApk.checkAvailability()` asked *before any camera
   is opened*. Anything other than `Supported` selects CameraX from the very first frame.
2. A runtime guard in `LiveViewModel` watching `SpatialLocalizationState`: if AR later reports
   `Failed`/`Unsupported`/`NotInstalled`, the session is stopped and the provider flips back to
   CameraX mid-session.

`PreviewProvider` (`Spatial` | `CameraX`) is exposed as a `StateFlow` so `LiveScreen` reacts without
re-owning the policy.

### Architecture

`SpatialLocalizationEngine` is the seam, same shape as `map.MapEngine` and `camera.RearCameraEngine`:
the app depends on the interface plus provider-neutral types, and only
`spatial/arcore/ArCoreSpatialLocalization.kt` imports `com.google.ar.core`. No `Session`, `Earth`,
`Pose` or `GeospatialPose` type ever crosses it.

- **In**: `probeAvailability()` (camera-free), `providesPreview`, `start(lifecycleOwner)`, `stop()`.
- **Out**: `state: Flow<SpatialLocalizationState>`.

`NoopSpatialLocalization` makes "AR cannot run here" a *value* the rest of the app handles normally —
the same reason `RouteState.Error` and `RearCameraState.Unavailable` are modeled rather than thrown.
It is what the JVM tests, and Live on a non-AR device, run on.

`LiveViewModel` gained the engine and the provider decision. It **still does not own navigation** —
Phase 4's guarantee is unchanged, and AR is additive on top of it.

### Session lifecycle

Tied to the `LifecycleOwner` exactly as the Phase 5 camera engine was, because that is where the
camera-leak risk lives:

- `start()` registers an observer and resumes if already RESUMED.
- `ON_RESUME` → `requestInstall()` support check → create `Session` once → configure Geospatial →
  `setCameraTextureName` (once GL produces it) → `resume()`.
- `ON_PAUSE` → `pause()`; `ON_DESTROY` → `close()`.
- `stop()` → pause, close, surface pause. Idempotent.
- **No duplicate sessions**: the session is created once and reused across resume/pause.
- `update()` is pumped **only from the GL thread** (the renderer), so it can never be called
  concurrently with itself.

Every ARCore throw is classified: `SessionUnsupportedException`/`UnavailableException` →
`Unsupported`; `NotYetAvailableException` → `NotInstalled`; `CameraNotAvailableException` →
`Failed(CameraUnavailable)`; anything else → `Failed(SessionCreationFailed)`. None reaches the UI as
a crash.

### Geospatial state mapping

| ARCore | `SpatialLocalizationState` |
| --- | --- |
| Camera not TRACKING, PAUSED | `Initializing` |
| Camera not TRACKING, STOPPED | `Limited(Initializing)` |
| Camera not TRACKING, other | `Limited(InsufficientFeatures / ExcessiveMotion / …)` |
| Camera TRACKING, Earth not `ENABLED` | `Limited(PoorGeolocation)` |
| Earth `ERROR_NOT_AUTHORIZED` / `ERROR_GEOSPATIAL_MODE_DISABLED` | `Failed(EarthUnavailable)` |
| Earth `ERROR_APK_VERSION_TOO_OLD` | `NotInstalled` |
| Camera + Earth TRACKING, pose not yet valid | `Tracking(null)` |
| Camera + Earth TRACKING with a pose | `Tracking(pose)` |

`Tracking(null)` exists deliberately: ARCore reports TRACKING before the first geospatial pose
arrives, and building a pose from missing values would be a lie. The Live status chip treats it as
still "Locating…".

### Localization quality

Quality is computed from **real accuracy values**, not from ARCore's coarse tracking state —
"TRACKING" says nothing about whether the *heading* is usable, which is the number that decides
whether AR content can be anchored. Thresholds live in `LocalizationQualityMapper` as constructor
parameters with documented defaults, so they can be retuned on real device data without touching
ARCore code:

| Level | Horizontal | Heading |
| --- | --- | --- |
| `Unavailable` | > 100 m, or **unknown** | > 45°, or unknown |
| `Acquiring` | fine, but not yet settled | fine |
| `Poor` | > 20 m or > 20° | |
| `Fair` | ≤ 20 m and ≤ 20° | |
| `Good` | ≤ 5 m and ≤ 5° | |

A **missing** accuracy is `Unavailable`, never "perfect" — treating unknown as excellent is the
easiest way to display a confident heading that is pure noise. A heading error beyond 45° is likewise
`Unavailable` rather than `Poor`, because "which way am I facing" is unknown, not merely imprecise.
`isAnchorable` is true only at `Good`.

### Heading / orientation

Three distinct concepts, deliberately not conflated:

| Concept | Source | Use |
| --- | --- | --- |
| Device heading | `GeospatialPose.heading` (ARCore) | AR/world-relative: which way the phone points |
| GPS movement bearing | `UserLocation.bearingDegrees` (Phase 1) | route-following: which way the car travels |
| Follow-camera bearing | `NavigationCamera` (Phase 4) | map orientation while navigating |

They diverge exactly when a driver is stationary or sliding — the moment guidance matters most — so
Phase 6 keeps `SpatialPose.headingDegrees` separate from the movement bearing and adds
`headingDeltaDegrees`/`headingsAgree` helpers rather than reusing the GPS value.

### Projection seam

Phase 5 declined a projection API as speculative. Phase 6 added `GeoProjector` because it then had a
real, measured input (a pose with heading and accuracy):

```
geographic route point → E/N offset from the user → rotate into the device frame → perspective divide → screen (x, y)
```

It is pure, GL-free and JVM-testable — the part of AR that is hardest to debug on a real device — and
returns `visible`/`onScreen` so a caller never draws an arrow for a point behind the driver or past
the field of view. It uses the same local flat-earth projection as `RouteProgressTracker` and
`NavigationCamera`, so all three agree.

**`GeoProjector` is now the degraded path, not the primary one.** It still exists and still works,
but every route arrow on a healthy system is placed from ARCore world anchors instead (see below).

### Phase 7: AR geospatial route anchoring

Phase 7 was originally committed as "Complete Phase 7 AR route anchoring" (`fec84b2`) while containing
**no anchor code at all** — no `Earth.createAnchor`, no `Anchor`, no world transform, no anchor
lifecycle. The only occurrence of the word "anchor" was Phase 6's `SpatialPose.isAnchorable`, a
boolean quality flag. So "road-anchored arrows" had nothing to be anchored to, and Phase 8's arrows
were necessarily placed by re-projecting raw lat/lng every frame.

The repair adds a real anchoring layer:

| Piece | File | Responsibility |
| --- | --- | --- |
| Models | `core/anchor/RouteAnchorModels.kt` | `WorldPose`, `WorldTransform` (position + quaternion), `AnchoredRoutePoint`, `RouteAnchorConfig` |
| Sampling | `core/anchor/RouteAnchorSampler.kt` | Which route points become control points. Pure |
| Policy | `core/anchor/RouteAnchorManager.kt` | Create / reuse / prune / quality-gate. Pure, via `AnchorBackend` |
| ARCore | `anchor/ArCoreAnchorBackend.kt` | The **only** file that calls `Earth.createAnchor` |

`com.google.ar.core` types never cross into `core/anchor` or into Compose. `ArCoreAnchorBackend`
creates genuine geospatial anchors via `Earth.createAnchor(latitude, longitude, altitude, quaternion)`
and reads `Anchor.getPose()` for a world-space transform in metres.

#### Why anchor positions are absolute, not driver-relative

The first implementation keyed anchors on route vertex index and spaced them out from the driver's
current position. That is subtly broken in a way that is invisible on a desktop and obvious in a car:
as the driver moves, the whole ladder of sample positions slides forward, every existing key stops
being wanted, and the **entire anchor set is destroyed and recreated on every location update**. The
visual result is a full arrow-set reset — precisely the anchor churn that makes world-locked content
shimmer instead of sitting still.

An earlier repair attempt then made density a *function of distance ahead* (tighter near the driver,
tighter near a maneuver) while keeping a stateless grid. That still failed, because a
forward-dependent thinning rule has to be seeded from somewhere, and the seed moves with the window.

The shipped rule is therefore fully **stateless and absolute**:

- Anchor *n* always lies at `n × anchorGridMeters` (5 m) along the route, whatever the driver is doing.
- Density is a modular rule on that grid index: keep every 3rd point (15 m) on a straight, every point
  (5 m) inside the maneuver band.
- The maneuver band is expressed in *absolute* route distance. As the driver advances, the
  distance-to-maneuver increases by exactly the amount the base distance increases, so the band's
  absolute position does not move.

Driving forward then **adds** anchors at the far end of the window and **retires** them at the near
end — a conveyor belt, not a rebuild. `RouteAnchorSamplerTest` and `RouteAnchorManagerTest` pin this
down, including a budget test asserting that 100 one-metre position updates create ≤ 20 anchors.

Per-driver density variation (near/far arrow spacing, turn density) still exists, but it belongs to
Phase 8's sampler, which applies it **per arrow**. The two layers deliberately do different jobs.

#### The anchor window

| Setting | Value | Reason |
| --- | --- | --- |
| `anchorGridMeters` | 5 m | The quantization every anchor snaps to |
| `spacingStraightMeters` | 15 m | Coarse is fine: anchors define a road's direction, not its surface |
| `spacingNearManeuverMeters` | 5 m | Extra control points on the approach stop arrows cutting the corner |
| `maneuverApproachBandMeters` | 40 m | How far back the tightened spacing applies |
| `behindWindowMeters` | 15 m | Needed so the first arrow (past a 6 m near cut-off) can be interpolated |
| `aheadWindowMeters` | 160 m | Covers Phase 8's 120 m arrow window with margin |
| `maxAnchors` | 20 | The real cost bound; ~12 are typical on a straight |

The cap is applied to anchors **ahead** of the driver, not to the head of the list. Taking the list
head would keep the behind-window anchors and push the far ones out, so the same far anchors would be
dropped and recreated as the driver advanced — the churn bug again, one layer up.

Anchors are created and pruned on route/progress/quality **changes**, never per frame. Creating an
ARCore anchor is a real cost (a server-refined object) and must never sit in the render loop.

#### Altitude: a fixed offset, deliberately

`roadHeightOffsetMeters` is a **constant 0.35 m lift, not road elevation**. ARCore's terrain and
rooftop anchors exist in the API but are far less reliable than geospatial ones — they need additional
imagery support and are frequently unavailable — so this layer anchors geospatially at the Earth
reference height and lifts by a fixed offset. The lift is applied **in world space**, not as a
screen-space nudge, so two anchors 10 m apart do not produce a step in arrow height at the boundary.

Consequence, stated plainly: **arrows will float or sink on real hills.** On a 5 % grade, a 120 m
arrow window drifts ±6 m against a 0.35 m lift. Terrain anchors are the fix and are not implemented.

### Phase 8: AR route arrows

Phase 8's pre-existing logic is unchanged and still owns *which* arrows exist and *which way* they
point: `ARArrowSampler` does route sampling, near/far spacing, turn density, the near cut-off, the
visible cap and the tangent heading; `ARArrowQualityGate` does hysteresis; `ARArrowRenderer` is the
only GL code. The repair changed exactly one thing — the **spatial placement seam**.

```
route geometry
  → RouteAnchorManager (Phase 7)          real Earth.createAnchor world poses
  → AnchoredArrowProjector.worldPoseAlongRoute   interpolate between control points
  → AnchoredArrowProjector.project         world → camera space → screen
  → ARArrowRenderer                        draw
```

`ARArrowPipeline.update` runs the Phase 8 sampler on **both** paths, then chooses placement:

| Priority | Path | When |
| --- | --- | --- |
| 1 | **Anchored** (`usingAnchors = true`) | Any tracked anchor exists **and** a camera world transform exists |
| 2 | **Degraded projection** (`usingAnchors = false`) | No anchors, anchors not yet tracked, no camera transform, or anchors do not reach as far as the arrows |

The fallback is **never silently preferred**. `ARArrowPipeline.usingAnchors` and
`ARArrowPipeline.degradedReason` state which path is live and, if not, why — so "arrows are showing but
they slide" is explainable rather than mysterious.

#### Interpolation

Anchors are intentionally sparse. `worldPoseAlongRoute` interpolates linearly **in world space**
between the two bracketing control points, which means the interpolation is a real line in ARCore's
frame and stays put as the device moves. Consequences, all covered by `AnchoredArrowProjectorTest`:

- Spacing stays metric and continuous; a 1 mm step in arrow distance never produces a jump.
- Arrows beyond the anchored window are **dropped, not extrapolated**. Inventing a position past the
  last anchor produces a confidently wrong chevron, which is the exact failure degraded anchoring is
  supposed to prevent.
- Partial coverage degrades gracefully: arrows inside the window are drawn from real world positions
  and the rest are dropped.

#### Orientation

Arrow heading is the **route tangent from geometry**, never the device heading. The camera's full
orientation (position *and* quaternion) is used only to build the view-relative transform — that is
what makes arrows swing across the screen when the phone turns. A position-only projection would leave
them pinned to screen centre, which is the classic symptom of AR content that is not actually
world-locked.

#### Overlay host

`ArCoreSpatialLocalization` now exposes a minimal `ArOverlayRenderer` interface
(`onSurfaceCreated()`, `drawFrame(alpha)`) plus `overlayAlpha`. The AR engine knows nothing about
anchors, arrows, geometry or rendering policy — only "draw this after the camera". `ARArrowPipeline`
implements it and is registered/unregistered by `ArPreviewSurface`, so it is the extension point later
phases reuse without touching the AR engine.

#### Reroute, arrival and Map ↔ Live

`MapViewModel` gained two plain callbacks, `onRouteReplaced` and `onNavigationEnded`, wired to
`LiveViewModel` in `OpenMapsApp` via a `DisposableEffect` (cleared on dispose, so recomposition cannot
stack duplicates). The Map feature stays unaware of AR; it only announces when the trip changes.

A reroute clears the anchor set and the arrow set **before** the new ones are built, so two routes are
never drawn at once. It deliberately does **not** restart the AR session: the camera and pose remain
valid, and rebuilding mid-drive would flicker the preview and risk a camera handoff.

| Moment | Anchors | Arrows |
| --- | --- | --- |
| Reroute | cleared, new route's created | cleared |
| Arrival / `stopNavigation` / destination clear | released | cleared |
| Leaving Live (`DisposableEffect` dispose) | released | cleared, overlay detached |
| Losing localization | retained (not destroyed) | retained where still valid |
| Poor quality | creation stops, existing kept | suppressed by the quality gate |

Anchors are deliberately **retained** when quality degrades. Destroying and recreating them on a brief
signal loss would visibly reset every arrow, which is worse than leaving the last good positions up.

### Fallback

Live degrades, it does not break:

| Condition | Result |
| --- | --- |
| ARCore unsupported / not installed | `probeAvailability()` → CameraX from frame one; HUD silent |
| Camera permission denied | existing Phase 5 permission panel |
| Session creation fails | `Failed` → runtime guard flips to CameraX |
| Earth unresolved (no network) | `Limited(PoorGeolocation)` → chip says "Improving accuracy…" |
| Poor GPS / poor VPS | `Limited` or `Tracking` with low quality → "Improving accuracy…" |
| Background/foreground | session pause/resume tied to lifecycle; no duplicate session |

Map mode is always available regardless: it never touches the AR engine.

### Debug diagnostics

`BuildConfig.DEBUG`-gated overlay showing AR state, horizontal/vertical/heading accuracy and the
geospatial coordinates. Stripped from release by the `if (BuildConfig.DEBUG)` guard. Nothing logs
location continuously — `ArCoreSpatialLocalization` logs only session lifecycle and failures, and no
coordinate is ever written to logcat.

## Phase 8 output contract

What the next phase can rely on, exactly as it stands at the end of Phase 8:

| Requirement | Provided by | Notes |
| --- | --- | --- |
| Stable geospatial pose | `SpatialPose.latitude/longitude/altitudeMeters` | From `Earth.cameraGeospatialPose`; `Tracking(null)` until first fix |
| Device orientation / heading | `SpatialPose.headingDegrees` (device-facing, true north) | Distinct from the GPS movement bearing — do not conflate |
| **World-space route anchors** | `RouteAnchorManager.anchorPoints()` → `List<AnchoredRoutePoint>` | Each has a real `WorldPose` once ARCore tracks it |
| **Camera world transform** | `ArCoreSpatialLocalization.cameraTransform()` | Position **and** quaternion; published per frame from the GL thread |
| **Anchor placement decision** | `ARArrowPipeline.usingAnchors`, `degradedReason` | Which path is live, and why if not |
| Anchor counts / churn | `RouteAnchorManager.createdCount` / `detachedCount` / `size` | Diagnostics only; not logged |
| Localization quality | `SpatialPose.quality`, `isAnchorable` | Only `Good` is anchorable |
| Accuracy values | `horizontalAccuracyMeters`, `verticalAccuracyMeters`, `headingAccuracyDegrees` | Raw, for filtering/hysteresis if needed |
| AR/Earth state | `SpatialLocalizationState` | `Tracking` / `Limited(reason)` / failure states |
| Geographic projection seam | `GeoProjector.project(world, userPose, viewport…)` | **Fallback only**; returns `x/y`, `distanceMeters`, `bearingDegrees`, `visible`, `onScreen` |
| Overlay extension point | `ArCoreSpatialLocalization.ArOverlayRenderer` | `onSurfaceCreated()` + `drawFrame(alpha)` |
| Navigation route geometry | `RouteResult.geometry`, `RouteManeuvers` (Phase 4) | Input to both anchor sampling and arrow sampling |
| Navigation state | `MapUiState.navigationState`, `LiveHudState` (Phase 4/5) | Live still does not own it |
| Live lifecycle | `ArPreviewSurface` + session bound to `LifecycleOwner` | `update()` pumped on the GL thread only |
| Camera ownership | `PreviewProvider` (`Spatial` \| `CameraX`) | Exactly one owner at a time |
| Trip lifecycle hooks | `MapViewModel.onRouteReplaced`, `onNavigationEnded` | Plain callbacks; Map stays AR-unaware |

**Still not present:** terrain/rooftop anchoring (so arrow altitude is a fixed lift, not real road
elevation), Streetscape geometry, and any anchor persistence across app restarts.

### Validation status

| Area | Status |
| --- | --- |
| Unit tests | 304 passing, 0 failures. Includes all pre-existing Phase 1–8 tests |
| `assembleDebug` / `lint` | Both pass. Lint warnings unchanged from the pre-existing baseline (40, all warnings) |
| Emulator (Pixel 8 API 35) | No crashes across launch, 5× Map↔Live, 3× background/foreground, navigation + off-route + reroute + stop. Camera open count stays at 1 — no leaks. ARCore is **not installed** on the emulator, so only the CameraX fallback and the `AR: Idle` state were observable |
| Physical ARCore device | **PENDING — no ARCore-capable device was available.** Nothing in this document about real spatial stability, road alignment, turn continuity or anchor drift has been observed in the real world |

Emulator testing proves nothing about spatial correctness. An emulator has no ARCore service, so
`Earth.createAnchor` was never executed even once; the anchored path was exercised only through unit
tests with a fake backend and real quaternion arithmetic.

**The first run on a physical device should treat the debug diagnostics overlay as the primary
diagnostic**, and should specifically check: that `usingAnchors` is true while driving, that
`RouteAnchorManager.size` stays near 12 and `createdCount` grows slowly, that arrows do not slide when
the phone turns, and that they do not float or sink on a gradient (expected — see the altitude note).

## Contextual places (Phase 9)

Restaurants, coffee, hotels, grocery, pharmacies, parking, gas and EV charging — surfaced near the
user (Map, not navigating) or near the active route (either mode, once a route exists), in both Map
and Live, with Live kept deliberately quieter than Map.

### Architecture

`core/places` is a new, self-contained layer, the same shape as `core/geocoding` and
`core/routing`: provider-neutral interfaces and models that the feature layer depends on, with the
provider-specific implementation swappable behind them.

```
core/places
├── PlaceModels              Place, PlaceCategory, PlaceMetadata, PlaceException   (pure Kotlin)
├── PlaceRepository          interface: nearbyPlaces(...), placesAlongRoute(...)    (pure Kotlin)
├── PlaceCategoryMapper      OSM tag <-> PlaceCategory, Overpass QL selectors       (pure Kotlin)
├── OverpassResponseParser   pure response-parsing logic, unit-tested without network
├── OverpassPlaceRepository  HTTP + JSON implementation (java.net + org.json)
├── PlaceCache               TTL cache keyed on a bucketed center + radius + categories (pure Kotlin)
├── RouteCorridor            nearest-segment distance, ahead/behind, corridor filter (pure Kotlin)
├── PlaceRanking             deterministic scoring (no ML)                          (pure Kotlin)
└── LivePlaceProjector       Live's geographic -> screen seam, reusing GeoProjector  (pure Kotlin)
```

`Place` (`id`, `name`, `category`, `latitude`, `longitude`, `address?`, `distanceMeters?`,
`routeDetourMeters?`, `openNow?`, `metadata`) is the only places-provider shape anything outside
`core/places` ever sees, exactly mirroring how `RouteResult`/`SearchResult` are the only shapes
their providers expose. `PlaceMetadata` holds the category-specific fields (`parkingType`,
`isPublicAccess`, `feeApplies`, `brand`, `connectors`) as nullable/empty-default fields on one flat
type rather than a per-category subtype hierarchy — most fields are absent most of the time, and a
`Place` is one shape regardless of category.

### Place data provider

`OverpassPlaceRepository` queries the public Overpass API (`overpass-api.de/api/interpreter`)
directly against OpenStreetMap data — free, open, no API key, the same OSM lineage already used for
the basemap (OpenFreeMap), geocoding (Nominatim) and routing (Valhalla). It POSTs an Overpass QL
query built from `PlaceCategoryMapper.overpassSelectors(categories)`, an `around:radius,lat,lon`
filter, and `out body center $MAX_ELEMENTS` — the request-side bound on how many POIs can come back,
so Places can never fetch an unbounded set. Both `node` and `way` elements are queried (a parking
lot or hotel is often mapped as a way, not a point; `out center` returns its centroid).

**Overture Maps was considered and rejected for this phase.** Overture distributes places as
cloud-hosted Parquet (S3/Azure blob storage) rather than a queryable nearby-search HTTP API, so
using it from an Android client at request time would mean standing up and operating a server-side
tile/query index first — real infrastructure, not a Phase 9 decision. Overpass already is a
request/response nearby-search API over the same open dataset the rest of the app depends on.

**Development-only constraints**, same category as the tile source, Nominatim and Valhalla: the
public instance has no SLA and a documented soft rate limit, and a descriptive `User-Agent` is sent
on every request per its usage expectations. **Do not ship to the public on it.** Before production,
move to a self-hosted Overpass instance or a contracted places provider — only `PlaceRepository`'s
implementation needs to change.

HTTP: `java.net.HttpURLConnection`, 10s connect/read timeout, POST with a form-encoded `data=`
body (Overpass QL queries can get long; GET with a query string risks URL-length limits a POST body
does not have). Failures are classified the same way as geocoding/routing: `NoNetwork`, `Timeout`,
`Server(code)`, `RateLimited` (429 and 504, Overpass's own "too busy" code), `Malformed`. A response
that is not valid JSON throws inside `OverpassResponseParser` and is mapped to `Malformed`; a
response with no matching elements is `Result.success(emptyList())`, never a failure.

**Partial / per-element failures never fail the whole parse.** `OverpassResponseParser` skips (not
throws for) any element with no tags, no resolvable coordinates, or a category
`PlaceCategoryMapper` does not recognize — one malformed or unsupported element cannot take the
whole response down. This is the actual shape "partial category failure" takes with Overpass: every
requested category comes back in one combined query/response, so a per-category HTTP failure isn't
possible, but a per-element data problem within that response is, and is handled at the parser.

### Category mapping

`PlaceCategoryMapper` holds the only OSM tag names outside the parser: a small table of
(`key`, `value`, `PlaceCategory`) rows, because OSM models one real-world category under more than
one tag value (`amenity=restaurant` and `amenity=fast_food` are both "restaurant"). `categoryFor`
returns null for anything unmapped — an **unsupported category**, which callers skip rather than
guess at. `overpassSelectors(categories)` builds the query-side tag filters for a requested
category set, deduplicated and in a stable (and therefore cache-friendly) order.

| `PlaceCategory` | OSM tags |
| --- | --- |
| `Restaurant` | `amenity=restaurant`, `amenity=fast_food` |
| `Coffee` | `amenity=cafe` |
| `Hotel` | `tourism=hotel`, `tourism=motel`, `tourism=guest_house` |
| `Grocery` | `shop=supermarket`, `shop=convenience`, `shop=grocery` |
| `Pharmacy` | `amenity=pharmacy` |
| `Parking` | `amenity=parking` |
| `Gas` | `amenity=fuel` |
| `EvCharging` | `amenity=charging_station` |

### Caching

`PlaceCache` is an in-memory TTL cache (default 5 minutes), keyed on center bucketed to ~550 m,
radius bucketed to 250 m, and the exact category set — so two queries a few tens of metres apart (a
user drifting slightly, two camera-idle/location ticks in a row) hit the same entry instead of each
firing a fresh request. No database, no persistence across process death: the same "Phase-sized
concern kept Phase-sized" reasoning that keeps `RecentSearchStore` a flat `SharedPreferences` list.
A failed refresh never evicts a good cached/`Ready` result — see "Network/failure handling" below.

### Nearby / route-aware filtering

`MapViewModel` always requests **every** `PlaceCategoryMapper.supportedCategories` from the
repository; `MapUiState.placeCategoryFilters` (defaulting to all categories) is a pure client-side
display filter applied in `recomputeVisiblePlaces`. This means toggling a category chip is instant
and **never** triggers a new network request — only a different query (center/radius/route) does.

Two fetch triggers, deliberately not more:

- **Not navigating**: `maybeFetchNearbyPlaces` fetches around the user's location, refetching only
  once they have moved more than `NEARBY_REFETCH_DISTANCE_METERS` (400 m) from the last fetch
  center — never on every fix, which is how "avoid refetching the same area continuously" is
  actually enforced.
- **A route exists** (selected or replaced by a reroute): `fetchPlacesAlongRoute` fetches once
  against `RouteCorridor.boundingQuery(route.geometry, corridorMeters, maxRadiusMeters)` — a center
  and radius covering the whole route's bounding box plus a corridor margin, capped at 8 km so a
  very long route can't trigger an unbounded-radius query — then filters the result to
  `ROUTE_CORRIDOR_METERS` (250 m) of the actual polyline via `RouteCorridor.filterToCorridor`, which
  also stamps `Place.routeDetourMeters`. This is a **corridor approximation**, not per-segment
  detour routing: one query per route, not a scrolling window re-queried as the trip progresses.

`RouteCorridor` uses the same local flat-earth (equirectangular) nearest-point-on-segment math as
`RouteProgressTracker`, so a place's distance to the route never disagrees with how the tracker
itself measures progress along it. `RouteCorridor.isAhead(place, geometry, snappedShapeIndex)`
classifies a place as ahead of or behind the driver by comparing its nearest-segment index to the
driver's own snapped index — reused by ranking below, not by the fetch (fetching does not filter on
direction; ranking does).

### Ranking

`PlaceRanking.rank(places, context)` is deterministic and explainable — no ML, by design. Score per
place: a category weight, minus a distance penalty (using `routeDetourMeters` when known, else
`distanceMeters`), minus a fixed penalty when navigating and the place is not in `aheadPlaceIds`.
Utility categories (parking/gas/EV) get a higher category weight than general categories **only**
while navigating; browsing weighs every category equally. `MapViewModel.recomputeVisiblePlaces`
computes `aheadPlaceIds` via `RouteCorridor.isAhead` only when navigating (there is no meaningful
"ahead" without a snapped position), ranks, then caps: `MAX_VISIBLE_PLACES_BROWSING` (40) or
`MAX_VISIBLE_PLACES_NAVIGATING` (20, deliberately lower — fewer, more relevant places mid-trip).

This is computed explicitly, not as a `MapUiState` derived property: ranking walks the route
geometry, so it must run only when an input actually changed (a fetch resolved, the category filter
changed, navigation started/stopped) — never on every recomposition or every GPS tick.

### Map mode rendering

`MapEngine.setPlaces(places: List<Place>)` is the extension point this codebase's docs already
flagged ("POI overlays -> `setPlaces(list)`, symbol layers") before Phase 9 existed.
`MapLibreMapEngine.setPlaces` renders a `GeoJsonSource` + `CircleLayer`, the same
"whole-collection-replace" approach as the destination marker and route line — never more than one
places layer, and an empty list clears it. Each feature carries `id` and `category` properties; the
circle's color comes from a **data-driven `Expression.match` on `category`**, one color per
`PlaceCategory`, smaller and less saturated than the destination marker so the destination still
reads as "the" pin.

**Deliberately a `CircleLayer`, not a `SymbolLayer` with per-category icons** — see
[Destination marker](#destination-marker)'s documented limitation: a programmatically added custom
icon consistently rendered nothing on this project's test emulator. Eight category icons would hit
the identical issue for the identical reason, so markers are color-coded circles instead, same as
the existing marker.

The places layer is added **below `ROUTE_TRAVELED_LAYER_ID`** (which `syncRoute` always creates,
even with no route loaded yet), so the destination marker and route line always render on top of
contextual places — navigation content stays visually dominant, per the Phase 9 density rule
below. Tap-to-select uses `MapLibreMap.queryRenderedFeatures` against just the places layer inside
`addOnMapClickListener`, so a tap on the map, the destination, or the route is never misread as a
place tap; a hit emits `MapEvent.PlaceTapped(placeId)`, mirroring how every other engine signal
already reaches `MapViewModel` through `MapEvent`.

**Density limiting, not pixel clustering**: Map already ranks and caps the list before it ever
reaches the engine (see Ranking above), which is the "clustering or density limiting if needed"
requirement in a form that is simple, deterministic and already unit-tested, rather than a second,
heavier algorithm doing the same job of "don't show too many."

### Live mode rendering

Live stays visually restrained by design: `LiveViewModel.updateLivePlaces` only ever produces
labels while `SpatialLocalizationEngine.state` is `Tracking` **with a non-null pose** — the CameraX
fallback (no AR) and `Tracking(null)` (AR running, pose not yet arrived) both simply show no place
labels, rather than guessing a position from GPS movement bearing or any other proxy. This is the
same "do not fake precision" reasoning Phase 6 applied to localization quality and Phase 7 applied
to the arrow/projection seam.

`LivePlaceProjector.project(places, pose, viewport…, maxVisible)` reuses `GeoProjector` — the pure,
GL-free geographic-to-screen seam Phase 6 built — rather than a parallel AR localization or
anchoring system. This is a deliberate choice of the **simpler screen-space fallback** over a
world-locked GL overlay: a place label is informational context (name, category, approximate
distance), not a safety-critical path cue, so a 2D screen-space position is enough, and it keeps
Live's contextual-places code entirely on the CPU side, unit-testable on the JVM with no GL and no
ARCore dependency — exactly like `GeoProjector` itself. Results are capped at `MAX_LIVE_PLACE_LABELS`
(3, "very low" density per the rule below), nearest first.

`LiveScreen`'s `ArPreviewSurface` recomputes the label set whenever the candidate list, the spatial
state, or the viewport size changes (not per frame) and renders them as small pill labels — name,
category dot, approximate distance, no giant cards — positioned at each label's projected point and
staggered vertically by rank so near-simultaneous labels do not stack exactly on top of each other
(a presentation offset, not a precision claim; `GeoProjector`'s vertical placement is a constant
horizon line, it has no pitch/elevation model). The maneuver HUD, maneuver arrow and trip summary
are unaffected — this overlay draws beneath them in composition order and never competes for the
top/bottom scrim regions they occupy.

### Parking

`PlaceCategory.Parking` captures, **only when OSM actually states it**: `parkingType` (OSM's
`parking` tag — surface/underground/multi-storey/street_side/...), `isPublicAccess` (from `access`),
and `feeApplies` (from `fee`). **No availability or occupancy is ever shown or implied** — OSM has no
such data, and Phase 9 does not invent it. A parking lot with no `parking`/`access`/`fee` tags at all
still appears (named, if OSM has a name; "Parking" as a fallback label otherwise) with that metadata
simply absent from the details sheet.

### Gas

Shows name, location, distance and `brand` (from OSM's `brand` or `operator` tag) when present.
**No fuel price is ever shown** — Phase 9 has no pricing data source, and the plan explicitly
forbids showing a price "unless backed by a real source."

### EV charging

Shows name, location, distance, and — only when OSM states them — `brand` (`operator`/`network`)
and `connectors` (every `socket:*` tag present, e.g. `socket:type2`, `socket:ccs`, sorted).
**No live occupancy, charger availability or charging speed is ever shown**; none of that is in the
OSM data this provider reads.

### Place details and navigation integration

`PlaceDetailsSheet` (Map mode) is a compact card — category dot + label, name, a distance line
(`routeDetourMeters` phrased as "off route" when known, else `distanceMeters` as "away"), one
metadata line built only from fields the provider actually supplied, and a single "Navigate"
action — deliberately not a Yelp-style profile. Tapping "Navigate" calls
`MapViewModel.onNavigateToPlace`, which converts the `Place` to a `SearchResult` and calls the
existing `onDestinationSelected` — **the same destination/route path Search already uses**, not a
second routing system. `PlaceCategoryFilterRow` is the "lightweight toggles" this phase calls for:
one chip per category (not a grouped taxonomy), client-side only (see Nearby/route-aware filtering
above).

### Visual density

The ordering this phase enforces end to end: **navigation arrows > maneuver guidance > critical
utility POIs > general POIs.** Concretely — the destination marker and route line always render
above places on the map; the Live maneuver HUD and trip summary are unaffected by place labels;
utility categories (parking/gas/EV) only outrank general categories while actually navigating; and
the two density caps are deliberately asymmetric (Map 40 browsing / 20 navigating; Live 3 always).

### Network / failure handling

`MapViewModel.fetchPlaces` cancels any in-flight places request before starting a new one (only the
latest call's result can land, the same pattern `requestRoute`/`requestReroute` already use), and
**a failure never clears a `Ready` result that is already showing** — it only becomes `PlacesState.Error`
when there was nothing good to fall back on yet. Every `PlaceException` maps to a `PlacesError`
(`NoNetwork`, `Timeout`, `ServerError`, `RateLimited`, `Malformed`) the same way routing/geocoding
errors do. Navigation, location and routing are completely unaffected by any places failure — Places
is additive context, never a core dependency, and nothing it does can block a trip.

### Privacy

Place queries send only the coordinates and radius needed for that one request — no continuous
location stream reaches the provider, and (as with geocoding/routing) nothing about a place query is
logged. `PlaceCache` holds results in memory only, never persisted, and is cleared when the process
dies.

### Verification status

58 new unit tests (233 total on this branch); `assembleDebug`, `testDebugUnitTest` and `lint` all
pass clean (0 lint issues). **Not yet run on an emulator or a physical device** — the fetch -> Map
marker -> tap -> details -> Navigate flow, the category filter chips, and Live's label
placement/readability while moving are all unverified manually. Treat this the same as Phase 6's
"unverified on a physical device" note: the code paths are tested in isolation, but the end-to-end
experience has not been watched run.

## Phase 10 output contract

What Phase 10 (traffic, road hazards, weather, lane guidance — per ROADMAP.md) can rely on, exactly
as it stands at the end of Phase 9:

| Requirement | Provided by | Notes |
| --- | --- | --- |
| Provider-neutral contextual-object pattern | `core/places` (`PlaceRepository`, `Place`, caching, corridor filtering, ranking) | The template to follow for a second contextual-data domain, not a dependency Phase 10 needs to reuse directly |
| Route-corridor filtering | `RouteCorridor.boundingQuery` / `filterToCorridor` / `isAhead` | Already generalized over "a list of geographic things near a route"; a hazard feed can reuse it as-is |
| Deterministic ranking/priority model | `PlaceRanking` | Same shape (category weight, distance penalty, ahead/behind) would extend to hazard severity vs. distance |
| Map rendering seam | `MapEngine.setPlaces` | A second `MapEngine` method (e.g. `setHazards`) follows the identical "whole-collection-replace `GeoJsonSource`+layer" pattern |
| Live rendering seam | `LivePlaceProjector` (wraps `GeoProjector`) | The same CPU-side projection works for any geographic point; a hazard icon/label would reuse it directly |
| Visual-density discipline | Map 40/20 caps, Live 3-label cap, layer ordering below route/destination | The precedent for "never let secondary context crowd out navigation," which a traffic/hazard layer must also respect |

**Still Phase 10 work, not present:** any live traffic/incident data source, weather data, lane-level
guidance, and speed-limit intelligence beyond already-existing static UI. None of Phase 9's code
assumes or blocks any particular choice there.

## Road intelligence (Phase 10)

Hazards, weather, speed limits and prioritized driver alerts — route-aware in both Map and Live,
Live kept deliberately quieter than Map, exactly mirroring Phase 9's places precedent.

### Architecture

`core/roadintelligence` is a new, self-contained layer, the same shape as `core/places` and
`core/routing`:

```
core/roadintelligence
├── RoadEventModels               RoadEvent, RoadEventType, RoadEventSeverity, RoadIntelligenceException (pure Kotlin)
├── TrafficModels                 TrafficCongestionLevel, TrafficCondition                      (pure Kotlin)
├── WeatherModels                 WeatherConditionType, WeatherCondition                         (pure Kotlin)
├── SpeedModels                   SpeedUnit, SpeedLimitInfo                                      (pure Kotlin)
├── LaneModels                    LaneDirection, Lane, LaneGuidance                               (pure Kotlin)
├── RouteAlert                    sealed alert type + RoadAlertPriority (deterministic ranking)   (pure Kotlin)
├── RoadIntelligenceState         the one state value Map/Live both read                          (pure Kotlin)
├── RoadIntelligenceRepository    interface: roadEventsAlongRoute, speedLimitNear, weatherNear     (pure Kotlin)
├── RoadEventRouteMatcher         corridor filter + cumulative route-distance stamping             (pure Kotlin)
├── RoadEventDeduplicator         collapses duplicate provider events by id                        (pure Kotlin)
├── RoadAlertActivation           distance-ahead calc, behind-driver suppression, approach distances (pure Kotlin)
├── RoadAlertHysteresis           confirm/exit debounce so alerts don't flicker on GPS noise        (pure Kotlin)
├── OsrmLaneGuidanceMapper        OSRM `intersections[].lanes` → LaneGuidance (not yet wired — see below)
├── RoadIntelligenceCache         three TTL caches (hazards/weather/speed), bucketed like PlaceCache (pure Kotlin)
├── OverpassRoadIntelligenceParser  hazard + speed-limit parsing, unit-tested without network
├── OpenMeteoWeatherParser        weather parsing, unit-tested without network
└── DefaultRoadIntelligenceRepository  HTTP + JSON implementation (java.net + org.json)
```

`RoadEvent` (`id`, `type`, `latitude`, `longitude`, `routeDistanceMeters?`, `severity`, `title`,
`description?`, `startTimeMillis?`, `endTimeMillis?`, `metadata`) is the only road-event shape
anything outside `core/roadintelligence` ever sees, mirroring `Place`/`RouteResult`. `RouteAlert`
is a sealed type (`Hazard | Weather | Lane | SpeedLimit | Congestion | Informational`) rather than
scattered nullable fields — the one shared type `RoadAlertPriority` ranks and `MapUiState`'s
`roadIntelligenceState.activeAlerts` renders.

### Providers: what is real, and what is honestly absent

Three independent capabilities, each optional and each backed (or not) by a real, free,
no-API-key data source — same "development-only, document the limits" category as every other
provider in this codebase:

| Capability | Source | What it actually is |
| --- | --- | --- |
| Hazards | Overpass API (same host `OverpassPlaceRepository` already calls) | Static OSM tags only: `highway=construction`/`construction=*` → `RoadEventType.Construction`, `hazard=*` → `RoadEventType.Other` (value carried verbatim). **Not** crash/debris/stalled-vehicle/closure data — OSM is a static map database and has no real signal for those. |
| Speed limits | Overpass API, nearest `highway=*` way's `maxspeed` tag | Real OSM data. A non-numeric `maxspeed` (`"none"`, `"walk"`, a zone name) is never translated into a guessed number — `SpeedLimitInfo` is only ever built from a value a provider actually stated. |
| Weather | Open-Meteo (`api.open-meteo.com`, free, no API key, no account) | Real current-conditions reading, mapped from Open-Meteo's documented WMO `weather_code` table plus a measured-wind-speed override for `HighWind`. `visibilityMeters` is always null — Open-Meteo's `current` block (unlike `hourly`) does not expose one, and this only ever requests `current`. |

**Traffic congestion has no source at all**, by design. `TrafficCondition`/`RoadEventType.Congestion`
exist as provider-neutral models specifically so a real traffic feed (a contracted provider, a state
511 feed, HERE/TomTom) can be plugged in behind `RoadIntelligenceRepository` later with zero
`MapViewModel`/UI changes — but wiring one in now would mean hardcoding a commercial provider with
no key-management story, which this phase explicitly avoids. `DefaultRoadIntelligenceRepository`
simply never produces congestion data rather than labelling static/absent data as live traffic.

**Lane guidance has a real, tested mapper with nothing to feed it.** `OsrmLaneGuidanceMapper` maps
the documented OSRM `intersections[].lanes` shape into `LaneGuidance`/`Lane`, and is unit-tested
against that real shape. It is **not wired into `ValhallaResponseParser`**: the native Valhalla
`/route` response this app requests (`costing=auto`, no `format` override) was inspected — including
the fixture captured from a live call in `ValhallaResponseParserTest` — and its
`trip.legs[].maneuvers[]` objects carry no lane-level field under any name. Valhalla can emit
OSRM-compatible output (which does carry `intersections[].lanes`), but only via a `format=osrm`
request parameter this app does not pass; switching the live request format is a routing-provider
change well beyond this phase's scope. `RoadIntelligenceState.laneGuidance` is therefore always null
today — see "Phase 11 prerequisites" below.

**Do not ship to the public on either live source.** Same category of open production decision as
the tile source, Nominatim, Valhalla and Overpass places — see "Decisions to revisit."

### Route matching and "distance ahead"

`RoadEventRouteMatcher.matchToRoute` drops events outside the corridor and stamps survivors with
`RoadEvent.routeDistanceMeters` — the cumulative distance **along the route**, computed once per
route (a prefix-sum pass) and from a proper perpendicular *projection* onto the nearest segment, not
a straight chord to the segment start (which would overcount by an event's lateral offset). This
reuses `RouteCorridor`'s nearest-segment math, so a hazard's distance to the route never disagrees
with how `RouteProgressTracker` measures the driver's own progress along it.

That stamped distance is what makes "distance ahead" a cheap per-tick subtraction rather than a
re-walk of the route geometry: `RoadAlertActivation.distanceAheadMeters(event, distanceTraveledMeters)`
is `event.routeDistanceMeters - distanceTraveledMeters`, null (suppressed) when negative — an event
behind the driver is never shown, full stop.

### Activation and hysteresis

`RoadAlertActivationConfig` holds per-type approach distances (collision-class hazards ~800 m,
informational hazards ~400 m, a severe closure's wider 3 km "known about but not yet prominent"
distance) — configurable, not hardcoded, per the brief's "make thresholds configurable." Speed
limit and weather are not distance-gated at all: they describe the driver's current position, not a
point somewhere ahead on the route.

Raw per-tick candidates then pass through `RoadAlertHysteresis`, which generalizes
`OffRouteDetector`'s confirm-sample pattern from one boolean to a set of ids: an alert needs two
consecutive ticks present before it activates, and two consecutive ticks *absent from the raw
candidates* before it is actually dropped (the confirmed alert keeps rendering through one
borderline sample in between). Without this, an alert sitting right at its activation boundary
would flicker on and off with ordinary GPS noise on every location update. `RoadEventDeduplicator`
collapses same-id duplicates from one provider response before any of this runs.

### Alert priority

`RoadAlertPriority.rank` is a fixed table, no ML: severe closure → collision-class hazard (crash,
debris, flooding, ice, or high-severity construction) → dangerous weather (≥ `High` severity) → lane
guidance → speed limit → congestion → everything else, with ties broken by a stable sort so
equal-priority alerts never reshuffle tick to tick.

### Caching and request cadence

`RoadIntelligenceCache` is three independent bucketed TTL caches (same shape as `PlaceCache`), one
per concern, because each has a different natural refresh cadence: hazards 3 minutes (~550 m
bucket), weather 20 minutes (~5.5 km bucket — weather doesn't vary block to block), speed limits 30
minutes (~165 m bucket — tight enough to change at an intersection). `MapViewModel` additionally
gates *when* it even calls the repository: hazards once per route (ready or rerouted, same as
places), weather only once the user has moved `WEATHER_REFETCH_DISTANCE_METERS` (8 km) from the
last fetch, speed limit only past `SPEED_LIMIT_REFETCH_DISTANCE_METERS` (150 m) — never on every GPS
tick for any of the three.

### Map mode rendering

`MapEngine.setRoadEvents(events)` is the `setPlaces`-shaped extension point ARCHITECTURE.md's Phase
9 section already predicted ("a second `MapEngine` method... follows the identical pattern").
`MapLibreMapEngine` renders a `GeoJsonSource` + `CircleLayer` colored by `RoadEventSeverity` (not
type — "how urgent" is the glanceable signal, and it means any future event type without a bespoke
color still renders sensibly), anchored **above** the places layer when it exists (a hazard should
outrank a general POI) and, like places, always below the route/destination layers.

`RoadAlertBanner` (new Compose surface) is Map's compact alert row: the single top-priority alert
(by `RoadAlertPriority`) plus a speed chip (current reading over posted limit, when known), placed
below the search bar/navigation banner so it never covers the route or destination — the section 11
density rule in the form every other Phase 10 surface also uses.

### Live mode rendering

Priority in Live is deliberately AR arrows > maneuver guidance > safety alerts > lane guidance >
speed info > places — the Phase 9 "visual density" ordering, extended by exactly one tier. Live's
entire road-intelligence surface (`LiveRoadAlertHud`) is two short lines, placed below the maneuver
card: one hazard/weather line (the top-priority alert, excluding the speed limit) and one persistent
speed line (current + posted limit). Never icons scattered over the camera feed, never more than
those two lines regardless of how many alerts are technically active — the same "restrained by
construction" rule Phase 9's 3-label cap and `LivePlaceProjector`'s "do not fake precision" already
established.

### Current speed

`UserLocation.speedMetersPerSecond` is a new optional field, populated in
`AndroidLocationRepository.toUserLocation()` from `android.location.Location.hasSpeed()/getSpeed()`
exactly like `accuracyMeters`/`bearingDegrees` already are — a **direct reading**, never derived from
consecutive fixes (a distance/time estimate from two noisy GPS points is a materially worse number
than a real one, and section 8 of the brief explicitly asks for the real reading when one exists).
Kept as a distinct concept from the posted speed limit everywhere it is surfaced, per the brief.

### Rerouting and traffic-aware ETA

Road intelligence never triggers a reroute by itself — the existing off-route hysteresis
(`OffRouteDetector`) is still the only thing that calls `requestReroute`, exactly as the brief
requires ("do NOT automatically reroute solely because a RoadEvent exists"). A reroute **is**
observed by this layer: `MapViewModel.requestReroute`'s success path resets `RoadAlertHysteresis`
(the old route's matched distances and confirm/exit streaks are meaningless against new geometry)
and refetches hazards against the new route, the same "stale corridor data is now wrong" handling
`fetchPlacesAlongRoute` already gets on a reroute.

`TrafficCondition.baseDurationSeconds`/`trafficAdjustedDurationSeconds` are kept as distinct,
independently-nullable fields precisely so a provider that cannot supply a traffic-aware estimate
leaves the adjusted figure null rather than this type inventing one — moot today, since no traffic
provider exists, but the contract a future one must satisfy.

### Failure isolation and privacy

Every one of the three repository calls fails silently from the ViewModel's point of view: a failed
hazards/weather/speed-limit fetch leaves the previous (possibly empty) value exactly where it was,
the same "never clear a Ready result" rule `fetchPlaces` already follows for places. No road
intelligence source is ever consulted inside the navigation tick's critical path — `onNavigationTick`
and `RouteProgressTracker` know nothing about this layer at all. Queries send only the coordinates
and radius needed for that one request; nothing is logged, and `RoadIntelligenceCache` holds results
in memory only, cleared when the process dies — the same posture as `PlaceCache`.

### Verification status

86 new unit tests (448 total on this branch, 0 failures) covering hazard/speed-limit/weather
parsing, route-distance filtering and the chord-vs-projection distinction, behind-driver
suppression, activation thresholds per type, hysteresis (confirm, exit grace, flicker resistance,
reset), deduplication, priority ordering, the three caches' independent TTLs, and `MapViewModel`
integration (route-ready fetch, movement-gated weather/speed-limit refetch, current-speed
population, two-tick hazard confirmation, stop/reroute cleanup). `assembleDebug`, `testDebugUnitTest`
and `lint` all pass clean (same 40-warning baseline as Phase 9, zero new warnings).

**Manually validated on the Pixel 8 API 35 emulator**: launch, Map search → destination → live
Valhalla route → Overpass places, Start navigation (maneuver banner correct), camera-permission
grant flow in Live, Live HUD (maneuver card + trip summary, road-intelligence HUD correctly silent
with no qualifying alerts at the tested location), Map ↔ Live ↔ Map switching while navigating — no
crashes, no exceptions in logcat. Not separately observed: an actual hazard/weather/speed-limit
alert rendering live (the tested route/location had none to surface, which — given Overpass/
Open-Meteo calls otherwise succeeded, evidenced by the real street names and place markers the same
network path returned — is consistent with there genuinely being no construction tag, no
sub-"Moderate" weather, and no tagged `maxspeed` way within range there, not with the pipeline being
broken). Worth a specific follow-up on a physical device or a route known to pass a tagged
construction zone.

## Error handling

- **Map unavailable**: a style load failure (`OnDidFailLoadingMap` before the style first loads) sets `error`, which shows "Map unavailable" with Retry. Search and Settings stay reachable. Retry reloads the style.
- Tile errors after the style has loaded do not raise the overlay; MapLibre degrades gracefully.
- **Location**: see the table above. Nothing throws; every state has UI.
- **Geocoding**: `SearchViewModel` never lets a `GeocodingException` reach the UI as raw text; it maps each to a `SearchError` with its own short, user-facing string (`search_error_no_network`, `_timeout`, `_rate_limited`, `_server`, `_malformed`). A blank or too-short query (`< SearchViewModel.MIN_QUERY_LENGTH`) never fires a request and is not treated as an error — it just shows nothing (or the recent list, if blank). A successful empty result list shows "No results found," not an error.
- **Routing**: `MapViewModel` maps every `RoutingException` to a `RouteError` the same way (`route_error_no_network`, `_timeout`, `_server`, `_malformed`, `_no_route`), shown as a short line in the bottom panel in place of distance/ETA. Missing origin is not an error either — `RouteState.WaitingForLocation` gets its own quiet copy ("Waiting for your location…") and resolves itself the moment a fix arrives. Missing destination never reaches `requestRoute` at all (it returns immediately if `selectedDestination` is null).
- **Camera (Phase 5)**: every CameraX failure is classified into a `CameraFailure` and rendered as a short explanation over an opaque backdrop — never a crash and never a bare black screen. `NoRearCamera`, `BindingFailed` and `InUse` each get their own copy, with retry and a way back to the map. Camera permission states get their own panel. The user can always return to Map: the X control is in the top bar regardless of camera state.
- **AR / spatial (Phase 6)**: every ARCore exception is mapped to a `SpatialLocalizationState` (`Unsupported`, `NotInstalled`, `PermissionRequired`, `Failed(reason)`) or a `Limited(reason)`; none is ever allowed to escape as a crash, and all of them fall back to the CameraX preview. Map mode never touches the AR engine, so the map, search, routing and navigation remain available no matter what AR does.
- **Contextual places (Phase 9)**: every `PlaceException` maps to a `PlacesError` the same way routing does; a failed fetch never clears an already-showing `Ready` result (see "Network/failure handling" under Contextual places). Navigation, location and routing are completely unaffected by any places failure.
- **Road intelligence (Phase 10)**: every `RoadIntelligenceException` is caught at the repository boundary and surfaces only as `Result.failure`; `MapViewModel` does not even map it to a UI-facing error type — a failed hazards/weather/speed-limit fetch is silently a no-op, leaving whatever was already in `roadIntelligenceState` untouched. No road intelligence source is ever read from inside `onNavigationTick`'s critical path, so navigation, rerouting, arrival and every other Phase 1-9 flow are provably unaffected by any of the three failing, being slow, or being absent entirely.

## Phase 11 output contract

What Phase 11 (environment-dependent behavior — city/highway/suburb/rural modes, per ROADMAP.md) can
rely on, exactly as it stands at the end of Phase 10:

| Requirement | Provided by | Notes |
| --- | --- | --- |
| Traffic state | `RoadIntelligenceState.traffic` (`TrafficCondition?`) | Model and seam exist; always null today — no traffic-congestion source is wired in (see "Providers" above) |
| Road events (hazards) | `RoadIntelligenceState.events` (`List<RoadEvent>`) | Route-matched, each stamped with `routeDistanceMeters`; construction + tagged-hazard only, from Overpass |
| Weather state | `RoadIntelligenceState.weather` (`WeatherCondition?`) | Real current-conditions reading from Open-Meteo, driving-relevant subset only |
| Lane guidance | `RoadIntelligenceState.laneGuidance` (`LaneGuidance?`) | Model + a tested OSRM mapper exist; always null — Valhalla's native response carries no lane data (see "Providers" above for the `format=osrm` prerequisite) |
| Speed limit | `RoadIntelligenceState.speedLimit` (`SpeedLimitInfo?`) | Real OSM `maxspeed` reading, nearest-way, never inferred |
| Current speed | `RoadIntelligenceState.currentSpeedMetersPerSecond` | Direct `Location.getSpeed()` reading, kept distinct from the posted limit |
| Alert priority model | `RoadAlertPriority.rank`/`sorted` | Fixed table, no ML; ready to rank any future alert type that slots into the existing severity bands |
| Route context | `RoadEventRouteMatcher`, `RoadAlertActivation` | "Distance ahead," corridor filtering and behind-driver suppression all generalize to any future point-like route context, not just hazards |
| Map/Live rendering seams | `MapEngine.setRoadEvents`, `RoadAlertBanner`, `LiveRoadAlertHud` | Each follows the established density-limited, navigation-never-covered pattern |

**Still Phase 11 (or later) work, not present:** any live traffic-congestion source, a wired
lane-guidance provider (requires either a Valhalla `format=osrm` request or a different routing
provider), and anything environment-classification-specific (city/highway/suburb/rural) — Phase 10
deliberately does not infer or branch on road environment anywhere; every threshold in
`RoadAlertActivationConfig` is a single fixed default regardless of context.

## Migrating to PMTiles / self-hosted tiles

Nothing in the Map screen needs to change; only the provider does.

1. Produce a US extract from a Protomaps basemap build with the `pmtiles` CLI (`pmtiles extract`) and host the single `.pmtiles` file on object storage behind a CDN. It must support HTTP range requests.
2. Host glyphs (fonts) and sprites yourself; vector styles reference them.
3. Generate a style (for example from `protomaps-themes-base`) whose source points at the archive.
4. Implement `PmtilesMapStyleProvider : MapStyleProvider` returning `MapStyleSource.InlineJson` (or a hosted `Url`) with `isDevelopmentOnly = false`, and construct it in `AppContainer` instead of `BuildConfigMapStyleProvider`.
5. **Verify native `pmtiles://` support in the MapLibre Native release you ship.** If it is not supported, serve tiles to the style over HTTP(S) with a small tile endpoint (a Worker or `martin`) in front of the archive; the app-side change is identical.

## Decisions to revisit

- Overlay navigation instead of a navigation library (see Lifecycle).
- Manual DI; adopt Hilt/Koin when the graph grows.
- Permission "already asked" flag lives in `SharedPreferences`; fine for one boolean.
- Dark map style: the default style is light. A dark navy basemap would match the brand better and needs a style decision with the tile provider.
- **Geocoding provider**: Nominatim's public instance is development-only (see [Geocoding](#geocoding)). Needs a production decision (self-hosted Nominatim/Photon vs. a contracted provider) before shipping, same open question as the tile source.
- **Destination marker is a plain circle, not a custom pin icon**, because of the `SymbolLayer` icon-rendering issue documented under [Destination marker](#destination-marker). Worth re-trying a custom icon on a physical device or a newer MapLibre release.
- Recents are capped at 8 and not synced across devices/accounts; fine until there's a reason for more.
- **Routing provider**: the public Valhalla demo instance is development-only (see [Routing](#routing)), same category of open question as the tile source and geocoding provider — needs a production decision before shipping.
- **Costing is hardcoded to `"auto"`** (driving); no mode picker exists. Revisit once the product needs walking/cycling.
- **No re-routing on GPS drift**: a route is requested once per destination selection (or once the first location fix arrives after one). It does not refresh as the user's position updates. That is an active-navigation concern, explicitly out of Phase 3's scope ("no active turn-by-turn navigation yet") and a Phase 4 prerequisite.
- **Places provider**: the public Overpass API instance is development-only (see [Place data provider](#place-data-provider)), same category of open question as the tile source, geocoding and routing providers — needs a production decision before shipping.
- **Places along a route are fetched once**, not re-queried as the trip progresses; a very long route's corridor query is capped at an 8 km radius, so places far along a long route may simply never be queried. Revisit if a product need for "scrolling window" corridor refresh emerges.
- **No real-time parking/EV availability**: Phase 9 shows only what OSM's static tags state (type, access, fee; network, connectors). A live availability feed is a distinct, larger integration, not attempted here.
- **Hazard/speed-limit provider (Phase 10)**: the public Overpass API instance is development-only (same category as Nominatim/Valhalla/Overpass-for-places), and hazard coverage is limited to what OSM states statically (construction + `hazard=*` tags) — no crash/debris/closure data exists without a live incident feed. Needs a production decision (self-hosted Overpass, or a contracted incident-feed provider) before shipping.
- **Weather provider (Phase 10)**: Open-Meteo's free tier is development-only in the same sense — no SLA has been confirmed for this app's expected volume. Needs a production decision before shipping, same open question as every other provider here.
- **No live traffic-congestion source**: `TrafficCondition`/`RoadEventType.Congestion` are modelled but nothing populates them. A contracted traffic provider (or a state 511 feed) is the natural fit behind `RoadIntelligenceRepository`, not attempted here.
- **Lane guidance is modelled but unwired**: Valhalla's native `/route` response has no lane-level data; wiring `OsrmLaneGuidanceMapper` up would need either a `format=osrm` request to Valhalla or a different routing provider — a routing-architecture decision, not a Phase 10 one.
