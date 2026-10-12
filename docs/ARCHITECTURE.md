# OpenMaps Architecture

OpenMaps is a United States-only, Android-first navigation app. This document describes what exists after **Phase 3 (routing engine)**. Turn-by-turn guidance, AR, parking, computer vision, AI and authentication do not exist yet.

> **Verification status.** Phases 1-3 have all been built, unit-tested, and manually validated end-to-end on a Pixel 8 API 35 emulator — Phase 3's pass included a live route request/response against the routing provider, a rendered route line, distance/ETA, camera fit, route replacement and route clearing. See [SETUP.md](SETUP.md#verification-checklist).

## Stack

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3 |
| Build | Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), AGP 8.7.3 |
| Map renderer | MapLibre Native Android `org.maplibre.gl:android-sdk:11.13.5` |
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
│   └── routing                          Phase 3 routing layer
│       ├── RoutingModels                Coordinate, RouteResult, RouteBounds, RoutingException   (pure Kotlin)
│       ├── RoutingRepository            interface                                                (pure Kotlin)
│       ├── ValhallaRoutingRepository    HTTP + JSON implementation (java.net + org.json)
│       ├── ValhallaResponseParser       pure response-parsing logic, unit-tested without network
│       └── PolylineDecoder              pure polyline6 decoder, unit-tested against a captured response
├── map                                  the ONLY package that knows about MapLibre
│   ├── MapModels                        CameraState, MapCommand, MapEvent, MapMode, MapError    (pure Kotlin)
│   ├── MapStyleProvider                 MapStyleSource + provider interface                     (pure Kotlin)
│   ├── BuildConfigMapStyleProvider      style from Gradle properties
│   ├── MapEngine                        interface the app talks to
│   ├── MapLibreMapEngine                MapLibre implementation
│   └── MapLibreHost                     Compose host (AndroidView + lifecycle + flow wiring)
└── feature
    ├── map       MapViewModel, MapUiState, RouteState, MapScreen, MapControls
    ├── live      LiveScreen (placeholder, no camera/AR)
    ├── search    SearchViewModel, SearchUiState, SearchScreen (real geocoding search)
    ├── settings  SettingsScreen
    └── splash    SplashScreen
```

Dependency direction: `feature -> map (interface + models), core`. `MapViewModel`, `MapUiState`, `SearchViewModel` and `SearchUiState` import **no** Android framework and **no** MapLibre types, which is why they can be unit-tested on the JVM. `core.geocoding.SearchResult` and `core.routing.RouteResult` are used directly as `MapEngine.setDestination`/`setRoute`'s parameter types, exactly mirroring how `core.location.UserLocation` is already used for `setUserLocation` — `map` depending on `core.*` pure-Kotlin models is an established pattern, not a new exception.

## MapLibre integration

`MapEngine` is the seam. The app depends on the interface and on engine-neutral types; `MapLibreMapEngine` translates them.

- **Commands in** (`MapCommand`): `MoveCamera(target, animated, durationMillis)`, `RetryStyle`, `FitBounds(bounds, paddingPx, durationMillis)` (Phase 3).
- **Events out** (`MapEvent`): `StyleLoaded`, `StyleFailed`, `UserGestureStarted`, `BearingChanged`, `CameraIdle`.
- **Location in**: `setUserLocation(UserLocation?)`.
- **Destination in** (Phase 2): `setDestination(SearchResult?)` — shows/hides/replaces the destination marker.
- **Route in** (Phase 3): `setRoute(RouteResult?)` — shows/hides/replaces the route line.
- **Insets in**: `setBottomInset(px)` keeps the required map attribution above the bottom panel.

Planned extension points, deliberately not declared yet: AR/map synchronisation (expose camera and projection), active-navigation camera tracking (Phase 4+).

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
- While Live (an opaque placeholder) is showing, the map keeps running underneath. Pausing it is a later-phase concern when Live becomes real.

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

## Error handling

- **Map unavailable**: a style load failure (`OnDidFailLoadingMap` before the style first loads) sets `error`, which shows "Map unavailable" with Retry. Search and Settings stay reachable. Retry reloads the style.
- Tile errors after the style has loaded do not raise the overlay; MapLibre degrades gracefully.
- **Location**: see the table above. Nothing throws; every state has UI.
- **Geocoding**: `SearchViewModel` never lets a `GeocodingException` reach the UI as raw text; it maps each to a `SearchError` with its own short, user-facing string (`search_error_no_network`, `_timeout`, `_rate_limited`, `_server`, `_malformed`). A blank or too-short query (`< SearchViewModel.MIN_QUERY_LENGTH`) never fires a request and is not treated as an error — it just shows nothing (or the recent list, if blank). A successful empty result list shows "No results found," not an error.
- **Routing**: `MapViewModel` maps every `RoutingException` to a `RouteError` the same way (`route_error_no_network`, `_timeout`, `_server`, `_malformed`, `_no_route`), shown as a short line in the bottom panel in place of distance/ETA. Missing origin is not an error either — `RouteState.WaitingForLocation` gets its own quiet copy ("Waiting for your location…") and resolves itself the moment a fix arrives. Missing destination never reaches `requestRoute` at all (it returns immediately if `selectedDestination` is null).

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
