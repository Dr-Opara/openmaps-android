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

## Next, not started (awaiting approval)

Tentative order; each needs its own scope decision.

1. **Production basemap**: pick and host the tile source (PMTiles/Protomaps or self-hosted vector tiles), glyphs and sprites; flip `devOnly` to false; dark navy style to match the brand.
2. **Production geocoding**: move off the public Nominatim instance (self-hosted Nominatim/Photon or a contracted provider) before shipping.
3. **Routing**: engine and data choice, route line rendering through `MapEngine`. (Phase 2 explicitly stopped before this.)
4. **Turn-by-turn guidance**: maneuvers, rerouting, voice.
5. **Live / camera-first view**: the product's differentiator; depends on routing and on map/camera synchronisation.
6. **Later layers**: traffic, parking, richer place intelligence.

## Phase 3 prerequisites

Before routing can start, these Phase 2 decisions need to be either confirmed or revisited:

- A real `SearchResult.id`/coordinate pair is already available as the route destination input — no new destination-selection plumbing needed, routing can consume `MapUiState.selectedDestination` directly.
- Decide whether routing needs a *second* point (origin) beyond "current location," and if so whether that reuses the search UI or needs its own entry point.
- The destination marker is a `CircleLayer`; a route line would be a natural second `GeoJsonSource`/layer pair on the same `MapEngine` — confirm whether it should share the destination source or use its own (recommend its own, so clearing the destination doesn't require also clearing the route).
- Pick the routing engine/data source (own open-question, independent of the geocoding provider decision above).

## Open questions

- Which production tile host, and what volume do we expect at launch?
- Which production geocoding provider, and at what volume?
- Is the permission prompt on first launch (current behaviour) the desired onboarding, or should a pre-prompt explain it first?
- Do we want a persisted camera across process death (currently restored only across configuration changes)?
- Should recent searches sync across devices/accounts, or stay per-device (current behaviour)?
