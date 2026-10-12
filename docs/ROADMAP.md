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

## Next, not started (awaiting approval)

Tentative order; each needs its own scope decision.

1. **Production basemap**: pick and host the tile source (PMTiles/Protomaps or self-hosted vector tiles), glyphs and sprites; flip `devOnly` to false; dark navy style to match the brand.
2. **Production geocoding**: move off the public Nominatim instance (self-hosted Nominatim/Photon or a contracted provider) before shipping.
3. **Production routing**: move off the public Valhalla demo instance (self-hosted Valhalla or a contracted provider) before shipping.
4. **Turn-by-turn guidance**: maneuvers, rerouting as the user drives, voice.
5. **Live / camera-first view**: the product's differentiator; depends on turn-by-turn and on map/camera synchronisation.
6. **Later layers**: traffic, parking, richer place intelligence.

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
