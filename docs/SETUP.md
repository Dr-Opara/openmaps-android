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
- Debug builds show a small diagnostics overlay (AR state, horizontal/vertical/heading accuracy, coordinates) in the bottom-right of Live. Use it to tune `LocalizationQualityMapper` thresholds against real accuracy distributions.
- Check `adb logcat -s ArCoreSpatialLoc` for session lifecycle and failures. Coordinates are never logged.
- A black Live screen with a working camera permission almost always means AR was selected and then failed; check `PreviewProvider` fell back to `CameraX`.

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

## Verification checklist

**Nothing below has been run.** The remote build environment had no Android SDK and could not reach Google Maven or Maven Central, so it could not compile the Android code, run the Android unit tests, run lint, or launch anything. What *was* done remotely: the pure-Kotlin core (`LocationModels`, `LocationRepository`, `MapModels`, `MapStyleProvider`, `MapUiState`, `MapViewModel`) compiled with the Kotlin 2.0.21 compiler against a stub `ViewModel`, and 35 behavioural checks mirroring the ViewModel unit tests passed in a throwaway harness; XML and resource references were validated statically. That is **not** a substitute for the steps here.

### Build

- [ ] Gradle sync succeeds
- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] `./gradlew :app:testDebugUnitTest` passes (304 tests across 18 classes)
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
