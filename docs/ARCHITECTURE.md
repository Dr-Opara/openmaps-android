# OpenMaps Architecture

OpenMaps is a United States-only, Android-first navigation app. This document describes what exists after **Phase 1 (map foundation)**. Routing, AR, parking, computer vision, AI and authentication do not exist yet.

> **Verification status.** Everything below was written in a remote environment that could not reach Google Maven, Maven Central or the Android SDK. The pure-Kotlin core (state, ViewModel, models) was compiled and exercised there; **the Android, Compose and MapLibre code has never been compiled or run.** See [SETUP.md](SETUP.md#verification-checklist).

## Stack

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3 |
| Build | Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), AGP 8.7.3 |
| Map renderer | MapLibre Native Android `org.maplibre.gl:android-sdk:11.5.2` |
| Map data | OpenStreetMap-derived vector tiles via a configurable style URL |
| Location | Platform `LocationManager` (no Google Play services) |
| State | `ViewModel` + `StateFlow` |
| DI | Manual `AppContainer` (no framework) |
| SDK levels | minSdk 26, targetSdk/compileSdk 35 |

Explicitly **not** used: Google Maps SDK, Mapbox Maps SDK, proprietary navigation SDKs.

## Layers and packages

```
com.openmaps.app
├── OpenMapsApplication / AppContainer   manual DI: LocationRepository, MapStyleProvider, prefs
├── MainActivity                         splash theme swap, edge-to-edge, hosts Compose
├── OpenMapsApp                          splash -> persistent shell (map + overlays)
├── core
│   ├── designsystem                     colors, theme, typography, glass modifier, brand mark
│   └── location                         Phase 0 location layer
│       ├── LocationModels               UserLocation, LocationPermissionState, LocationStatus   (pure Kotlin)
│       ├── LocationRepository           interface + LocationUpdate                              (pure Kotlin)
│       ├── AndroidLocationRepository    LocationManager implementation
│       └── LocationPermissions          OS permission -> LocationPermissionState (+ "already asked" flag)
├── map                                  the ONLY package that knows about MapLibre
│   ├── MapModels                        CameraState, MapCommand, MapEvent, MapMode, MapError    (pure Kotlin)
│   ├── MapStyleProvider                 MapStyleSource + provider interface                     (pure Kotlin)
│   ├── BuildConfigMapStyleProvider      style from Gradle properties
│   ├── MapEngine                        interface the app talks to
│   ├── MapLibreMapEngine                MapLibre implementation
│   └── MapLibreHost                     Compose host (AndroidView + lifecycle + flow wiring)
└── feature
    ├── map       MapViewModel, MapUiState, MapScreen, MapControls
    ├── live      LiveScreen (placeholder, no camera/AR)
    ├── search    SearchScreen (placeholder, no geocoder)
    ├── settings  SettingsScreen
    └── splash    SplashScreen
```

Dependency direction: `feature -> map (interface + models), core`. `MapViewModel` and `MapUiState` import **no** Android framework and **no** MapLibre types, which is why they can be unit-tested on the JVM.

## MapLibre integration

`MapEngine` is the seam. The app depends on the interface and on engine-neutral types; `MapLibreMapEngine` translates them.

- **Commands in** (`MapCommand`): `MoveCamera(target, animated, durationMillis)`, `RetryStyle`.
- **Events out** (`MapEvent`): `StyleLoaded`, `StyleFailed`, `UserGestureStarted`, `BearingChanged`, `CameraIdle`.
- **Location in**: `setUserLocation(UserLocation?)`.
- **Insets in**: `setBottomInset(px)` keeps the required map attribution above the bottom panel.

Planned extension points, deliberately not declared yet: route rendering (GeoJSON line layer), POI overlays (symbol layers), AR/map synchronisation (expose camera and projection).

### Lifecycle and view ownership

- One engine owns one `MapView`. Compose creates the engine with `remember` and the view in `AndroidView(factory = ...)`; there is no `update` block, so ordinary state changes never touch the view.
- `MapLibreHost` registers a `LifecycleEventObserver`. Lifecycle replay brings the view up to the current state; `MapLibreMapEngine` maps events to `MapView.onCreate/onStart/onResume/onPause/onStop/onDestroy` with a small stage guard so calls are never duplicated or out of order, and `release()` walks the view down if the composable leaves while the activity lives.
- `onLowMemory` is forwarded through `ComponentCallbacks2`.
- **Search, Settings and Live are layered over an always-composed Map** rather than being navigation destinations. A navigation library would dispose the map composition on every screen change and force a style reload. Adopt one when the screen count justifies it and the map can be hoisted.
- While Live (an opaque placeholder) is showing, the map keeps running underneath. Pausing it is a Phase 3 concern when Live becomes real.

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

Gestures (pan, pinch zoom, double-tap, two-finger rotate, two-finger pitch) are MapLibre's built-in gestures with zoom clamped to 2-20. The built-in compass and logo are disabled; OpenMaps draws its own compass.

The blue dot is MapLibre's `LocationComponent` with its own location engine disabled and `CameraMode.NONE`; we feed it from our location layer through `forceLocationUpdate`, so there is one source of location truth.

## State model

`MapUiState` holds `currentLocation`, `locationPermission`, `locationStatus`, `cameraState`, `selectedMapMode`, `isMapReady`, `error` and `dismissedPrompt`; `isLocationLoading` and `locationPrompt` are derived. One-shot effects that need an Activity (`RequestLocationPermission`, `OpenAppSettings`, `OpenLocationSettings`) are `MapUiEvent`s on a buffered channel.

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
