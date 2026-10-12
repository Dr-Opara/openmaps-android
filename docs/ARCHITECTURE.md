# OpenMaps Architecture

OpenMaps is a United States-only, Android-first navigation app. This document describes what exists after **Phase 8 (AR route arrows on ARCore world anchors)**, which was built on a repaired Phase 7 (AR geospatial route anchoring). Parking, computer vision, AI and authentication do not exist yet.

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

## Error handling

- **Map unavailable**: a style load failure (`OnDidFailLoadingMap` before the style first loads) sets `error`, which shows "Map unavailable" with Retry. Search and Settings stay reachable. Retry reloads the style.
- Tile errors after the style has loaded do not raise the overlay; MapLibre degrades gracefully.
- **Location**: see the table above. Nothing throws; every state has UI.
- **Geocoding**: `SearchViewModel` never lets a `GeocodingException` reach the UI as raw text; it maps each to a `SearchError` with its own short, user-facing string (`search_error_no_network`, `_timeout`, `_rate_limited`, `_server`, `_malformed`). A blank or too-short query (`< SearchViewModel.MIN_QUERY_LENGTH`) never fires a request and is not treated as an error — it just shows nothing (or the recent list, if blank). A successful empty result list shows "No results found," not an error.
- **Routing**: `MapViewModel` maps every `RoutingException` to a `RouteError` the same way (`route_error_no_network`, `_timeout`, `_server`, `_malformed`, `_no_route`), shown as a short line in the bottom panel in place of distance/ETA. Missing origin is not an error either — `RouteState.WaitingForLocation` gets its own quiet copy ("Waiting for your location…") and resolves itself the moment a fix arrives. Missing destination never reaches `requestRoute` at all (it returns immediately if `selectedDestination` is null).
- **Camera (Phase 5)**: every CameraX failure is classified into a `CameraFailure` and rendered as a short explanation over an opaque backdrop — never a crash and never a bare black screen. `NoRearCamera`, `BindingFailed` and `InUse` each get their own copy, with retry and a way back to the map. Camera permission states get their own panel. The user can always return to Map: the X control is in the top bar regardless of camera state.
- **AR / spatial (Phase 6)**: every ARCore exception is mapped to a `SpatialLocalizationState` (`Unsupported`, `NotInstalled`, `PermissionRequired`, `Failed(reason)`) or a `Limited(reason)`; none is ever allowed to escape as a crash, and all of them fall back to the CameraX preview. Map mode never touches the AR engine, so the map, search, routing and navigation remain available no matter what AR does.

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
