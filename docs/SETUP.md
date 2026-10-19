# OpenMaps Setup

## Prerequisites

- **Android Studio** recent enough to support AGP 8.7.x (Ladybug or newer). Its bundled JDK is fine; the project targets Java/Kotlin 17 bytecode.
- **Android SDK Platform 35** and build tools (Android Studio offers to install them on first sync).
- An **emulator or device on Android 8.0 (API 26) or newer**. For location testing use a physical device or an emulator with a mock location set.
- Internet access on the machine and the device (Gradle dependencies, map style and tiles).

No API keys are needed for the default configuration.

## Open and run

1. Open the `OpenMaps/` folder in Android Studio and let Gradle sync.
2. Pick a device and run the `app` configuration, or from a terminal:

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # MapViewModel unit tests
./gradlew :app:lintDebug            # Android lint
./gradlew :app:dependencies --configuration debugRuntimeClasspath   # inspect for conflicts
```

The Gradle wrapper (8.14.3) was generated offline; the first run downloads that distribution.

### First sync: things to check

The project was written without being able to resolve dependencies. If sync or compile fails, the most likely causes, in order:

1. **A version in `gradle/libs.versions.toml` does not exist or conflicts.** Versions were chosen conservatively. Bump AGP/Kotlin/Compose/MapLibre to current releases if needed (keep the Kotlin Compose compiler plugin version equal to the Kotlin version).
2. **A MapLibre API name differs in your version.** All MapLibre calls are confined to `app/src/main/kotlin/com/openmaps/app/map/MapLibreMapEngine.kt`. Calls to double-check against the release you resolve: `MapLibre.getInstance`, `MapLibreMapOptions.createFromAttributes(context)`, `MapView.addOnDidFailLoadingMapListener`, `LocationComponentOptions` builder methods (`accuracyColor`, `accuracyAlpha`, `foregroundTintColor`, `pulseEnabled`, `pulseColor`), and the `LocationComponent` property names.
3. A Compose API moved between BOM versions (for example the `LifecycleEventEffect` and `LocalLifecycleOwner` imports from `lifecycle-runtime-compose`).

## Changing the map style

The style is selected at build time and read through `MapStyleProvider`. Put overrides in `~/.gradle/gradle.properties` (keeps the repo provider-neutral) or pass them with `-P`:

```properties
openmaps.style.url=https://example.com/styles/my-style.json
openmaps.style.name=My provider
openmaps.style.devOnly=false
```

```bash
./gradlew :app:installDebug -Popenmaps.style.url=https://example.com/style.json
```

- **Default:** OpenFreeMap "liberty" (`https://tiles.openfreemap.org/styles/liberty`), OpenStreetMap data, no key. **Development only**: free public instance, no SLA, terms for production volume unconfirmed.
- **Do not** point the style at `tile.openstreetmap.org`; its usage policy does not allow app traffic.
- **API keys:** if a provider needs one, put it in the URL through a Gradle property and never commit it. A key shipped inside an APK can be extracted; for production prefer a proxy or a self-hosted source.
- The map style, sprites, glyphs and tiles must be reachable over HTTPS; cleartext traffic is disabled.
- To move to PMTiles or self-hosted tiles, see "Migrating to PMTiles" in [ARCHITECTURE.md](ARCHITECTURE.md#migrating-to-pmtiles--self-hosted-tiles).

## Stadia Maps production provisioning

OpenMaps uses **MapLibre** as the renderer and **Stadia Maps** as the approved production vector-tile provider.
The public OpenFreeMap style remains only as a development fallback when no Stadia key is configured.

Create a Stadia Maps property/API key, then provide it outside source control:

```properties
STADIA_MAPS_API_KEY=<your Stadia Maps API key>
```

Supported locations are a Gradle property, the `STADIA_MAPS_API_KEY` environment variable, or the
git-ignored root `local.properties` file. The default production style ID is `alidade_smooth`;
override with `-Popenmaps.stadia.style=<style-id>` if needed.

Before creating a production release, run:

```powershell
.\gradlew.bat verifyProductionMapConfiguration
```

The task fails if the Stadia key is absent, the map is still marked development-only, or the approved
Stadia style host is not active.

### Offline route corridor

When Stadia is active, OpenMaps automatically keeps one MapLibre offline region around the selected
route. A new route/reroute replaces the old region. The app uses a **90 MiB total-resource stop
threshold**, intentionally below Stadia's 100 MB/device mobile tile-caching ceiling. Short trips use
more detailed zoom levels; long routes reduce max zoom to keep the corridor bounded. The offline
region is deleted when the trip is ended/cleared.

This is degraded-navigation support, not a promise that an arbitrary state/country has been
downloaded. MapLibre continues to observe provider cache headers and the app revalidates when online.

## Location

- Permissions declared: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`. No background location.
- Location updates run only while the app is visible and permission is granted.
- Emulator: Extended controls (...) > Location, set a point or route. A real fix may take a few seconds to arrive.

## Camera (Live mode)

- `CAMERA` is declared but the `android.hardware.camera.any` feature is **not required**, so OpenMaps still installs on camera-less devices; Live simply explains and the map/navigation keep working.
- The camera dialog appears **only when you first open Live**, never on launch.
- Emulator: the virtual scene camera works; use `adb emu geo fix <lon> <lat>` to simulate movement. Note the platform de-duplicates identical consecutive fixes, so vary the coordinates slightly when testing off-route/reroute behaviour, or the sample never arrives.
- To test a denial: `adb shell pm revoke com.openmaps.app android.permission.CAMERA`, then background and resume the app.

## AR / spatial localization (Live)

- ARCore is declared `optional`; the app installs and runs without it, and Live falls back to the CameraX preview.
- **The default Pixel 8 AVD cannot test AR.** It reports no `android.hardware.camera.ar` feature and has no `com.google.ar.core` package installed, so `probeAvailability()` returns `NotInstalled`/`Unsupported` and Live stays on CameraX. This is a valid test of the fallback path, not of AR.
- To verify AR for real you need a **physical ARCore-certified device** (Google Play Services for AR present). `adb shell pm list packages | grep google.ar` should show `com.google.ar.core`.
- Debug builds show a small diagnostics overlay in the bottom-right of Live, extended in Phase 12A:
  which camera provider is active (`Camera: Spatial`/`CameraX`), a compact label naming the exact
  ARCore condition (never a bare state name), horizontal/vertical/heading accuracy, coordinates,
  active anchor counts, and arrow-pipeline counts. Use it to tune `LocalizationQualityMapper`
  thresholds against real accuracy distributions.
- Check `adb logcat -s ArCoreSpatialLoc` for session lifecycle and failures. Coordinates are never logged.
- A black Live screen with a working camera permission almost always means AR was selected and then failed; check `PreviewProvider` fell back to `CameraX`.
- **`AR: Idle` that never changes, even though the camera/HUD work fine**: the overlay's "Camera:"
  line will read `CameraX` — the ARCore engine was never started at all. Phase 12A found and fixed
  two real, 100%-reproducible causes of exactly this, confirmed on a physical Samsung device (see
  ARCHITECTURE.md's "AR stuck at Idle" for the full trace with log evidence):
  1. A transient `ArCoreApk.checkAvailability()` first result (`UNKNOWN_CHECKING`) being treated as
     permanently unsupported. `LiveViewModel` now re-probes for ~1.5s and upgrades to the AR surface
     if it resolves `Supported`.
  2. `ArCoreSpatialLocalization` being constructed with the `Application` context, from which an
     `Activity` can never be resolved — this made session creation fail **every single time**, with
     no exception and no log line, which is what "AR: Session creation failed" with nothing further
     used to mean. Now resolved from the `LifecycleOwner` passed to `start()` instead.
  - If the overlay shows `AR: Session creation failed` with a real exception in `adb logcat -s
    ArCoreSpatialLoc` mentioning `GooglePlayServicesLocationLibraryNotLinkedException`:
    `play-services-location` is missing from `app/build.gradle.kts` (should already be present after
    Phase 12A; if this still happens, check the dependency actually resolved).
  - If the overlay shows `AR: Earth unavailable (not authorized)`: the session and Geospatial mode
    both work, but this app is not authorized to call the ARCore Geospatial API, so `Earth` never
    tracks and no anchor is ever created. This is **account provisioning, not a code bug** — it is
    the only remaining blocker to AR arrows rendering. Follow
    [ARCore Geospatial provisioning](#arcore-geospatial-provisioning-google-cloud) below, then
    rebuild.

## ARCore Geospatial provisioning (Google Cloud)

ARCore's Geospatial mode is a **Google Cloud service**, not a local AR feature. Without an
authorized API key the `Session` still starts and Geospatial still reports as configured, but
`Earth` reports `ERROR_NOT_AUTHORIZED`, never tracks, and `Earth.createAnchor` is never called — so
AR arrows silently never appear. This is the single remaining blocker to AR arrows.

This is the **only** part of Phase 12A that needs a human with a Google account. The repository
already carries the receiving end: `AndroidManifest.xml` declares the `com.google.android.ar.API_KEY`
meta-data that ARCore reads, and `app/build.gradle.kts` injects its value.

### What this project needs

| Item | Value |
|---|---|
| Android package name | `com.openmaps.app` |
| Debug signing certificate SHA-1 | `F2:D2:9F:2D:FE:0A:0A:90:80:58:3E:EC:3B:BB:5E:C2:9E:A9:A1:40` |
| Debug signing certificate SHA-256 | `D7:26:66:B2:1D:AA:C3:45:BC:BA:EE:8B:DF:44:A7:89:9C:69:D2:27:CB:A4:C4:68:B7:D7:B3:28:BB:0E:CE:B0` |
| Certificate DN | `C=US, O=Android, CN=Android Debug` |
| API to enable | **ARCore API** |
| Manifest meta-data | `com.google.android.ar.API_KEY` |

> **Exact paste location:** the repository root `local.properties` file, on a line reading
> `ARCORE_API_KEY=<your key>`. That file is already in `.gitignore` (`/local.properties`), so
> the key cannot be committed by accident. Nothing needs editing in the app source — the manifest
> entry and the Gradle manifest-placeholder plumbing are already committed.

> The fingerprints above are from `~/.android/debug.keystore`. They are **machine-specific** — the
> standard Android debug key is generated per machine. If you build on a different computer, get that
> machine's fingerprints before creating the restriction:
> ```powershell
> keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android -alias androiddebugkey
> ```
> A **release** build signed by your own key needs a **second** API key restricted to the release
> certificate, because the package+fingerprint pair must match the key actually signing the APK.

### Google Cloud steps

1. Open the [project selector](https://console.cloud.google.com/projectselector) and create a project
   (or pick an existing one).
2. Open **APIs & Services → Library**, search **ARCore API**, and click **Enable**.
   - You do **not** need *Maps SDK for Android*. OpenMaps renders with MapLibre, so the separate
     `com.google.android.geo.API_KEY` entry shown in Google's codelab does not apply here.
3. **APIs & Services → Credentials → Create credentials → API key.** Copy the key.
4. Edit the key → **Edit** → **API restrictions → Android apps** → **Add an item**:
   - Package name: `com.openmaps.app`
   - SHA-1 certificate fingerprint: `F2:D2:9F:2D:FE:0A:0A:90:80:58:3E:EC:3B:BB:5E:C2:9E:A9:A1:40`
   - Then set **Application restrictions → Android apps** (not "None") and save.
   - Keep **API restrictions → Restrict key → ARCore API**.
   - If it still fails, Google's own troubleshooting page says to temporarily set restrictions to
     **None** to prove the key itself works, then re-add them.
5. Put the key in `local.properties` (git-ignored — never commit it):
   ```properties
   ARCORE_API_KEY=AIza...your_key_here
   ```
   Alternatives: `-PARCORE_API_KEY=...` or an `ARCORE_API_KEY` environment variable. Resolution
   order is gradle property → environment variable → `local.properties`.
6. Rebuild and reinstall: `.\gradlew.bat --stop`, `clean`, `assembleDebug`.
7. Verify the key actually reached the APK:
   ```powershell
   D:\andriod\build-tools\36.0.0\aapt2.exe dump xmltree app\build\outputs\apk\debug\app-debug.apk --file AndroidManifest.xml | Select-String "API_KEY"
   ```
   (Substitute your SDK path.) Then outdoors, the Live overlay should stop reading
   `AR: Earth unavailable (not authorized)`.

### Two things this will still not fix

- **ARCore privacy notice.** Google requires that apps using the Geospatial API *"present the user
  with a prompt to acknowledge and allow the use of data from their device."* OpenMaps does not yet
  show one. It does not affect functional testing, but it is a release blocker and is still open.
- **Coverage.** Geospatial needs VPS coverage or a good open-sky GPS fix. VPS is not available
  everywhere, and `Earth.trackingState` can stay non-`TRACKING` for reasons unrelated to authorization.

## AR route anchors and arrows (Phases 7/8)

These require a physical ARCore device. There is **no emulator substitute**: without the
`com.google.ar.core` package, `Earth.createAnchor` is never called even once, so nothing about
anchoring, arrow stability or road alignment can be checked.

Go **outdoors with a clear sky**. ARCore's geospatial Earth solution is a visual-positioning system; it
will not initialize indoors, under cover, or against a blank sky.

What to look for while driving or walking slowly:

| Check | Expected | If it fails |
| --- | --- | --- |
| `usingAnchors` (diagnostics overlay) | Stays true while tracking | Anchors are not being created or not being tracked; check Earth state and network |
| `RouteAnchorManager.size` | Roughly 12 on a straight, more near a turn | Below that means anchors are failing; far above means they are not being retired |
| `createdCount` growth | Slow — a handful per 100 m | Fast growth means anchors are churning, which shows as arrows resetting |
| Arrows while turning the phone | Sweep across the view | If they stay pinned to the centre of the screen, the camera orientation is not reaching the projector |
| Arrows on a slope | Expect them to float or sink | **Known limitation, not a bug**: altitude is a fixed 0.35 m lift, not road elevation. Terrain anchors are not implemented |
| After a reroute | Old arrows gone, new ones on the new road | Ghost arrows mean `onRouteReplaced` did not fire |
| On arrival / Stop | No arrows, no lingering anchors | Anchors must be released; ARCore should stop refining |

Two things are deliberately noisy and are not faults:

- A short "no anchors yet" period at the start of a trip — ARCore needs a few seconds to establish
  Earth tracking before the first anchor resolves. The diagnostics chip should say "Improving
  accuracy…".
- The arrow set dropping to nothing when localization quality drops below `Fair`. The
  `ARArrowQualityGate` hysteresis is doing its job: arrows that cannot be trusted are worse than no
  arrows.

When the anchored path cannot be used, `ARArrowPipeline.degradedReason` carries the reason
(`no anchors created yet`, `anchors not yet tracked`, `no camera world transform`, or `anchors did not
cover the arrow window`). Arrows will still draw via the per-frame geographic projection fallback, but
they will slide and jitter with the GPS fix — that is degraded mode, not working road anchoring, and it
should not be reported as a success.

## Road intelligence (Phase 10 + production traffic)

- `HERE_API_KEY` is server-side in Supabase and powers production traffic-aware routing, live
  incidents/closures, exit/signpost guidance and the capability-gated lane-data path. The key must
  never be copied into the Android client or committed to Git.
- Static hazards and speed limits still use Overpass as an additive fallback; weather uses
  Open-Meteo. Failure of those context providers never blocks navigation.
- Route Preview shows traffic delay only when the routing provider supplied a real traffic-aware
  duration and free-flow duration.
- A severe provider-confirmed crash/closure ahead can trigger one traffic-aware recalculation; the
  existing route remains usable if rerouting fails.
- LaneLock renders only when HERE Route Matching returns real lane attributes and at least one lane
  is compatible with the active maneuver. Recommended lanes are blue; nonrecommended lanes are
  muted. No lane row means no trustworthy provider data — never a guessed lane layout.
- Exit/signpost wording comes from the routing provider and appears in both Map and Live guidance.
- Run `.\scripts\production-routing-acceptance.ps1` before calling routing/traffic production
  ready. It validates worldwide HERE routing, live traffic connectivity and lane-provider access.

## Environment modes (Phase 11)

City / Highway / Suburb / Rural, with `Unknown` as the safe fallback. Classification needs a **real
drive on a physical device with network** — it reads live OSM road classification from the same public
Overpass instance Places and Road intelligence already use.

- No API keys needed; same Overpass endpoint as above.
- **The emulator cannot demonstrate this.** With a synthetic `adb emu geo fix` position there is no
  real road context, so the mode stays `Unknown` — which is the correct safe fallback (it pins
  `EnvironmentProfiles.unknown` to the exact pre-Phase-11 constants) but proves nothing about
  detection.
- Classification only runs **while navigating**. Before Start, and after arrival, the mode is
  `Unknown` by design: there is no route shape to read.
- **Highway requires genuinely controlled-access OSM road class**, never speed. To test it, route
  onto a real `highway=motorway`/`motorway_link` or `trunk`/`trunk_link` way and confirm
  `RoadClass.isControlledAccess` matches the road you are on (check
  [openstreetmap.org](https://www.openstreetmap.org) first). A motorway can read `Unknown` if Overpass
  has no data for that tile — that is a data gap, not a classifier bug.
- **Hysteresis: 3 consecutive ticks.** A mode must hold before it switches, so a single noisy reading
  never flips behaviour. Do not expect an instant switch when crossing a city boundary; expect it
  within a few seconds of sustained evidence.
- **A mode switch must never restart navigation.** Verify the trip survives: the maneuver banner,
  remaining distance and elapsed time all keep counting through a City→Highway transition.

What to watch, in order of likelihood of being the thing that is wrong:

| Check | Expected | If it fails |
| --- | --- | --- |
| Mode reaches anything other than `Unknown` | Yes, on a real drive | Overpass returned nothing, or you are not navigating |
| Motorway reads `Highway` | Yes | The nearest way is not actually controlled-access at your position |
| Dense Manhattan grid reads `City` | Yes | Maneuver spacing is driven by the route's own maneuvers — a sparse route through a city can read `Suburb` |
| Long sparse rural route reads `Rural` | Yes, if nearby places are also ≤ 1 | Requires *both* long segments and low density |
| Mode change mid-trip | Trip continues uninterrupted | Navigation must never depend on classification |

There is no debug overlay for the environment mode today; `MapUiState.drivingEnvironment` is exposed in
the ViewModel and is the thing to assert on in a test.

## Verification checklist

**Nothing below has been run.** The remote build environment had no Android SDK and could not reach Google Maven or Maven Central, so it could not compile the Android code, run the Android unit tests, run lint, or launch anything. What *was* done remotely: the pure-Kotlin core (`LocationModels`, `LocationRepository`, `MapModels`, `MapStyleProvider`, `MapUiState`, `MapViewModel`) compiled with the Kotlin 2.0.21 compiler against a stub `ViewModel`, and 35 behavioural checks mirroring the ViewModel unit tests passed in a throwaway harness; XML and resource references were validated statically. That is **not** a substitute for the steps here.

### Build

- [ ] Gradle sync succeeds
- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] `./gradlew :app:testDebugUnitTest` passes (529 tests as of Phase 11)
- [ ] `./gradlew :app:lintDebug` has no errors
- [ ] `dependencies` output shows no unexpected version conflicts (look at `androidx.lifecycle`, `kotlinx-coroutines`, and MapLibre's transitive libraries)
- [ ] `./gradlew :app:assembleRelease` succeeds (R8 with MapLibre; try a release build early)

### On a device (physical preferred)

First launch, location permission **not** granted:

- [ ] OS splash then OpenMaps brand splash (navy, mark, "See What's Ahead"), then the map
- [ ] System location dialog appears once; the map is already usable behind it
- [ ] Map shows the style tiles; attribution is visible above the bottom panel

Allow **precise**:

- [ ] "Finding your location..." pill, then a blue dot appears
- [ ] Camera flies to your position once
- [ ] Walking/moving (or emulator route) moves the dot but not the camera
- [ ] Pan away: the camera stays where you left it
- [ ] Recenter button animates back smoothly, keeping zoom (at least 14)
- [ ] Pinch zoom, double-tap zoom, two-finger rotate, two-finger tilt all work
- [ ] Compass appears only when rotated; tapping it returns north smoothly and then hides
- [ ] The blue dot colour looks right and its pulse/accuracy ring is acceptable

Permission states:

- [ ] Choose **approximate only** (Android 12+): dot still appears
- [ ] Deny once: "Location access is off" card, Allow re-prompts, map still browsable
- [ ] Deny again / "don't ask again": "Location is blocked" with Open settings; granting there and returning updates automatically
- [ ] Revoke permission in system settings while backgrounded: dot disappears on return

Location services and failures:

- [ ] Turn location **off** (quick settings): "Location is turned off" card; "Turn on" opens settings; turning it on while the app is open recovers without a restart
- [ ] Indoors/no fix for 20 s: "Can't find your location" with Try again
- [ ] Dismiss a card with X: stays hidden until the situation changes

Live mode (camera):

- [ ] No camera dialog on launch; it appears only when Live is first opened
- [ ] Allow: rear-camera preview fills the screen, upright in portrait, with the top bar, HUD and summary readable over it
- [ ] Maneuver row shows an arrow, "in 382 ft", the instruction, and the following maneuver as "Then ..."
- [ ] Bottom summary shows the destination and remaining distance/time, and both decrease as you move
- [ ] Map | Live and back, **8+ times rapidly**: no crash, and the camera is released each time (`adb shell dumpsys media.camera` shows no `com.openmaps.app` client on the Map screen)
- [ ] Back to Map mid-navigation: the route, the blue dot and the navigation banner are all still there — nothing restarted
- [ ] Background (home) then foreground while the camera is open: preview returns, no crash
- [ ] Drive off the route while in Live: "Off route" appears, the stale trip summary disappears, and the preview keeps running
- [ ] Stay off route: "Rerouting..." appears briefly, then the maneuver and figures update to the new route
- [ ] Reach the destination while in Live: "You've arrived" card with "Back to map"; the camera stays live and the app does not close
- [ ] Revoke camera permission (see above) then resume: the "Camera access is off" panel appears; no crash, and Map is always reachable
- [ ] Rotate the device while in Live: the preview stays aligned and the HUD stays readable

AR localization (physical ARCore device required — see above):

- [ ] On a device **without** ARCore: Live opens the CameraX preview normally, no localization chip, no crash
- [ ] On an ARCore device: first open of Live may prompt to install "Google Play Services for AR"; after which Live opens with the AR preview
- [ ] AR preview looks identical to the CameraX preview (same camera image), with no black frame or flicker on the handoff
- [ ] Debug overlay in the corner shows `AR: Tracking` and horizontal accuracy that **improves outdoors** and degrades indoors
- [ ] The chip shows "Locating…" then settles to "Ready" (or stays "Improving accuracy…" indoors)
- [ ] Navigation HUD, rerouting and arrival all behave exactly as on the Map screen while AR runs
- [ ] Background/foreground while AR is tracking: session pauses and resumes, no duplicate camera client
- [ ] Map | Live repeatedly: exactly one camera owner at a time (`adb shell dumpsys media.camera` shows a single `com.openmaps.app` client, and none on the Map screen)
- [ ] Rotate to landscape: the AR preview stays aligned

Map failure:

- [ ] Airplane mode, then cold start: "Map unavailable" with Retry; Search and Settings still open
- [ ] Re-enable network, tap Retry: the map loads; the blue dot returns (confirms `LocationComponent` survives a style reload)

App behaviour:

- [ ] Map | Live toggle works; Live shows the camera permission prompt; Back (and the X in the Live top bar) returns to the map
- [ ] "Where are you going?" opens Search destinations (placeholder); back returns **without** the map reloading
- [ ] Settings shows location access, map style with the "Development only" label, attribution, version
- [ ] Rotate the device: camera and dot are restored; no crash
- [ ] Background then foreground: location resumes, no duplicate dots, no crash
- [ ] No visible overlay/z-order glitch over the map (if there is, try `textureMode(true)`)

## Troubleshooting

- **Blank grey map, no error:** the style loaded but tiles did not; check the device network and that the style URL's tile and glyph hosts are reachable.
- **"Map unavailable" immediately:** the style URL is wrong or unreachable (Logcat tag `Mbgl`/MapLibre shows the HTTP error).
- **Dot never appears on emulator:** set a location in Extended controls; the emulator provides no fix by default.
- **R8 stripping in release:** MapLibre ships consumer rules; add keep rules to `app/proguard-rules.pro` only if a release-only crash proves it necessary.


## LaneLock: OSM + Mapbox

Production LaneLock no longer depends on HERE Route Matching.

The lane gateway combines:
- OpenStreetMap `turn:lanes`, `turn:lanes:forward`, and `turn:lanes:backward` through Overpass.
- Mapbox Directions lane arrays for the same short upcoming-route trace.

Set the Mapbox token as a Supabase Edge Function secret, never in the Android APK or repository:

```powershell
npx supabase secrets set MAPBOX_ACCESS_TOKEN=<your-mapbox-public-access-token> --project-ref upcfmhcfuagloqlcmezr
npx supabase functions deploy lane-guidance --project-ref upcfmhcfuagloqlcmezr --no-verify-jwt
```

The gateway confidence rule is fail-closed:
- OSM + Mapbox agree -> high confidence, show LaneLock.
- One trustworthy source only -> medium confidence, show LaneLock.
- Both present but conflict -> return no guidance and hide LaneLock.
- Missing/ambiguous evidence -> hide LaneLock.

HERE remains the production provider for traffic-aware routing, incidents, alternatives, ETA, exits, and signposts.


## Global Places

Global Places uses the existing server-side `HERE_API_KEY` through the OpenMaps `places` Edge Function. No HERE provider credential is added to the Android APK.

Deploy the gateway:

```powershell
npx supabase functions deploy places --project-ref upcfmhcfuagloqlcmezr --no-verify-jwt
```

Run worldwide acceptance:

```powershell
.\scripts\production-places-acceptance.ps1
```

The production path is HERE Browse + OSM aggregation behind the gateway, with direct Android Overpass as the last-resort fallback.


## Live Trip Sharing

The Android app uses the OpenMaps `trip-share` Edge Function. The Supabase publishable key is safe for the mobile client; session owner/share capability tokens are generated at runtime and are not committed.

Deploy:

```powershell
npx supabase functions deploy trip-share --project-ref upcfmhcfuagloqlcmezr --no-verify-jwt
```

Run production lifecycle acceptance:

```powershell
.\scripts\production-trip-sharing-acceptance.ps1
```

The public viewer link contains only the share capability. Android retains the separate owner capability in memory for live updates and explicit revocation.


## Community Reports

The Android app uses the OpenMaps `community-reports` Edge Function. Reports are short-lived and moderated through confidence/feedback rather than treated as authoritative provider data.

Production acceptance:

```powershell
.\scripts\production-community-reports-acceptance.ps1
```

GitHub Actions runs this acceptance together with Android assemble, unit tests and lint for pull requests targeting `production/platform-v1`.


## Neural Navigation Voice

The OpenMaps `voice` Edge Function keeps the neural TTS provider credential server-side. Android never receives the provider key.

Required Supabase secret:

```text
OPENAI_API_KEY
```

The function uses the OpenAI Speech API with `gpt-4o-mini-tts`, requests low-latency WAV audio and returns it to the app. If the secret, provider or network is unavailable, Android automatically falls back to the platform TextToSpeech engine; navigation itself never waits on voice.

The app includes a user-facing disclosure that the neural navigation voice is AI-generated.


## Worldwide Localization

OpenMaps declares these Android app locales:

- `en-US`
- `en-GB`
- `es`
- `fr`
- `ar`
- `he`

On Android 13+, Settings > Navigation > App language opens the OS per-app language settings for OpenMaps. Arabic and Hebrew use Android's native RTL configuration.

Distance and speed units can remain `Automatic (regional)` or be overridden to Imperial/Metric. Automatic mode uses US imperial in the United States, Liberia and Myanmar; UK imperial uses miles/yards/mph; other regions use metric.

Production localization acceptance:

```powershell
.\scripts\production-localization-acceptance.ps1
```

The existing Places and neural-voice production acceptance suites also carry locale-specific requests, including Arabic/Hebrew voice synthesis.


## Production release signing

OpenMaps does not commit a release keystore, alias, or password. The Android build reads production
signing material only from Gradle properties, environment variables, or git-ignored
`local.properties`.

Required values:

- `OPENMAPS_RELEASE_STORE_FILE` — path to the production keystore, relative to the repository root
  or an absolute path supported by the build environment.
- `OPENMAPS_RELEASE_STORE_PASSWORD`
- `OPENMAPS_RELEASE_KEY_ALIAS`
- `OPENMAPS_RELEASE_KEY_PASSWORD`

Before producing a release artifact, run:

```powershell
.\gradlew.bat verifyReleaseSigningConfiguration
.\gradlew.bat bundleRelease
```

or on a Unix CI runner:

```bash
./gradlew verifyReleaseSigningConfiguration bundleRelease
```

The verification task fails if any value is missing or the configured keystore file does not exist.
Signing passwords are never written to BuildConfig, Android resources, the manifest, or source
control.

The production keystore and its passwords must be provisioned through the release owner's secure
credential store/CI secret manager. Do not place them in GitHub source, chat transcripts, screenshots,
or committed Gradle files.
