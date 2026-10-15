# OpenMaps Field Test Report

## STATUS: PARTIAL — DEVICE NOW AVAILABLE, STATIONARY/INDOOR SESSION ONLY

Phase 12 was blocked for lack of a physical device. A Samsung device became available during
Phase 12A and was used for a real, interactive on-device session (not emulator) — but **stationary,
indoors, driven via `adb` screenshots/taps**, not a driving field test. No driving data, no battery
endurance figures, no road-alignment observations, and no "while moving" AR behavior appear in this
document, because the session never left a desk. Sections 3 (clean install) through part of 12 (two
Map↔Live↔Map cycles) below are filled in from that real session. Everything requiring actual driving,
a long session, or outdoor sky view remains `NOT RUN` and is still an open item.

Phase 12 is **still not complete** — see [Exit criteria](#exit-criteria) — but Phase 12A's on-device
session did find and fix two real, 100%-reproducible app bugs that no amount of further emulator or
unit testing could have surfaced (see §3a), and identified the one remaining blocker to AR arrows
rendering at all: Geospatial API provisioning (account-level, not a code fix — see ROADMAP.md's
Open questions).

---

## 1. Build under test

| Item | Value |
|---|---|
| Commit SHA | `0c1194a` |
| Branch | `main` |
| Working tree | clean |
| Application ID | `com.openmaps.app` |
| versionName / versionCode | `0.1.0-phase1` / `1` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| MapLibre Native | **11.13.5** |
| ARCore | **1.56.0** |
| CameraX | 1.4.2 |
| AGP / Kotlin / Gradle | 8.7.3 / 2.0.21 / 8.14.3 |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK size | 60.72 MB (63,665,078 bytes) |
| APK SHA-256 | `8FC4151E4B92C7BD8A30EF4D334BD9FE05D1907C4335617F14C277C2E8CB783C` |
| APK built | 2026-10-14 14:46:16 |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 |

## 2. Device (Phase 12A — connected mid-phase)

| Item | Value |
|---|---|
| Device model | Samsung `SM-S948U1` (`m3quew`/`m3q`), serial `R3GL500BV8M` |
| Android version | 16 (API 36), build `BP4A.251205.006` |
| ARCore version on device | 1.57.262640093 (Play Store-installed) |
| Connection | USB, `adb`, driven via `adb shell input tap`/`screencap`/`logcat` — no on-screen manual interaction by the tester except unlocking the device |
| Session type | **Stationary, indoors, desk-based.** Camera pointed at a laptop keyboard throughout; no outdoor sky view, no driving |

### Device probe evidence

```
> adb.exe devices -l
R3GL500BV8M   device product:m3quew model:SM_S948U1 device:m3q transport_id:3

> adb shell pm list packages | grep ar.core
package:com.google.ar.core

> adb shell dumpsys package com.google.ar.core | grep versionName
    versionName=1.57.262640093

> adb shell pm list features | grep -i camera
feature:android.hardware.camera ... (no android.hardware.camera.ar entry — see note below)
```

The absence of `android.hardware.camera.ar` from `pm list features` was investigated as a possible
root cause and **ruled out**: `ArCoreApk.checkAvailability()` logged `SUPPORTED_INSTALLED` on every
probe during this session (confirmed via a temporary, now-permanent `Log.i` in
`ArCoreSpatialLocalization.probeAvailability()`), so this device's ARCore/Play Services determination
does not depend on that local feature flag. The real blockers were the three issues in §3a.

## 3. Non-field validation performed

Executed from a clean state on 2026-10-14, Windows, against Gradle 8.14.3.

| Step | Command | Result |
|---|---|---|
| Clean | `.\gradlew.bat clean` | BUILD SUCCESSFUL |
| Build | `.\gradlew.bat assembleDebug` | BUILD SUCCESSFUL |
| Tests | `.\gradlew.bat :app:testDebugUnitTest --rerun --no-build-cache` | BUILD SUCCESSFUL |
| Lint | `.\gradlew.bat lint` | BUILD SUCCESSFUL |

| Metric | Value |
|---|---|
| Unit tests | **529 passing**, 0 failures, 0 errors, 0 skipped |
| Test classes | 42 |
| Main Kotlin sources | 116 |
| Test Kotlin sources | 44 |
| Lint | 0 errors, 40 warnings |

Lint warnings are dependency-freshness and hygiene only, none behavioural:

| Issue | Count |
|---|---|
| `GradleDependency` (newer library version available) | 33 |
| `AndroidGradlePluginVersion` (newer AGP available) | 3 |
| `ObsoleteSdkInt`, `UnusedResources`, `ModifierParameter`, `DataExtractionRules` | 1 each |

### Build issues encountered (environment, not application)

Neither is an OpenMaps defect; both are reproducible-workflow hazards on Windows worth knowing
before field testing.

| # | Symptom | Root cause | Resolution |
|---|---|---|---|
| E1 | `:app:clean` failed — `IOException: Unable to delete directory app\build`, naming `intermediates\lint-cache\...\migrated-jars\*.jar` | Running Gradle daemon held open file handles on the lint-cache jars; a stopped/incompatible daemon was also resident | `.\gradlew.bat --stop`, then `clean` succeeded |
| E2 | `:app:lintReportDebug` failed — `FileSystemException ... is being used by another process` | Same handle contention as E1, triggered because `clean` had failed and left the cache locked | Resolved by the daemon stop; subsequent `lint` wrote `lint-results-debug.html` successfully |

## 3a. Phase 12A — real on-device session (not field testing: stationary, indoors)

Build validated on the Phase 12A working tree (uncommitted — see ROADMAP.md's Phase 12A entry):
`assembleDebug`, `testDebugUnitTest` (547 passing, 0 failures — up from 529), `lint` (0 errors, 40
warnings, same baseline) all pass clean.

Installed via `adb install -r`, launched via `adb shell am start`, driven via `adb shell input tap`
against coordinates read from `adb shell uiautomator dump` (not guessed), screenshots via
`adb exec-out screencap`, and `adb logcat` for exceptions/crashes.

**Exercised, with no crash** (crash buffer and `FATAL EXCEPTION` both checked clean across the
whole session): first launch, location permission already granted (device blue dot appeared),
category chips row, "Explore what's around you" CTA → `NearbyExploreSheet` opened (confirmed via
`uiautomator dump`: "Nearby" header, category filter row, and a correctly-rendered
`PlacesState.Error` empty-state message — "Nearby places unavailable right now"), destination
selection ("Katy, Texas") → Route Preview with the new Directions + Start two-button block (filled
blue Start, confirmed via `uiautomator dump` bounds and screenshot) → Start → Live (camera opened,
maneuver HUD appeared, trip summary correct) → Map → Live again → Map (route/progress preserved
across all transitions, confirmed via the maneuver banner's remaining-distance/time).

**AR: the full diagnostic chain in ARCHITECTURE.md's "AR stuck at Idle" was walked end-to-end on
this device, live, rebuilding and reinstalling between each discovery:**

| Attempt | Debug overlay read | Finding |
|---|---|---|
| 1 (pre-fix code) | `Camera: CameraX` / `AR: Idle (not started)` | Provider never selected AR; logcat confirmed zero `ArCoreSpatialLoc` activity |
| 2 (`lastSpatialFailure` fix only) | `Camera: CameraX` / `AR: Session creation failed (fell back)` | `probeAvailability` logged `SUPPORTED_INSTALLED`, but session creation still failed silently — traced to `context.findActivityOrNull()` always returning null (Application context, not an Activity) |
| 3 (Activity-resolution fix) | same label, but now with a full stack trace | `GooglePlayServicesLocationLibraryNotLinkedException` — ARCore Geospatial mode requires `play-services-location` linked into the binary; it was not a dependency |
| 4 (dependency added) | `Camera: CameraX` / `AR: Earth unavailable (not authorized) (fell back)` | Session now creates successfully and Geospatial mode enables (`"ARCore session created with Geospatial mode enabled"` in logcat) — but `Earth.earthState` is `ERROR_NOT_AUTHORIZED`: no Google Cloud project has the ARCore Geospatial API enabled for this app's package name/signing certificate |

Attempt 4's result is the current, accurate state: **two real app-code bugs are fixed; one external
provisioning requirement (Geospatial API authorization) remains, and it blocks arrows on every
device, not just this one, until resolved** — see ROADMAP.md's Open questions for the exact steps.
This was not reachable by reasoning about the code alone; each layer only became visible once the
one before it was fixed and retested live.

**Not exercised in this session** (stationary/indoor, no driving): road alignment, anchor stability
while moving, altitude/lift behavior, rerouting, arrival, GPS drift, environment mode transitions,
road intelligence alerts rendering live, weak/lost network recovery, 20× Map↔Live↔Map, screen
lock/unlock mid-navigation, long session, battery, thermal. All remain open per the §4 checklist.

Note for the field run: run `.\gradlew.bat --stop` **before** `clean`, and do not treat E1/E2 as an
app regression if they reappear.

---

## 4. Field procedure — mostly NOT RUN (rows 3–7 partially covered by §3a's stationary session)

Everything below is required for Phase 12 to be considered complete. Rows 3–7 got a partial,
stationary/indoor pass during Phase 12A (§3a) — re-run them for real during an actual drive, since
none of the "while moving" aspects (perf under motion, GPS behavior, etc.) were tested. Everything
else is written so it can be executed directly once a driving session is possible.

### Session header (fill per session)

```
Device:            ______  Android: ______  ARCore on device: ______
Commit SHA:        ______  APK SHA-256: ______
Session start/end: ______  Duration: ______  Battery start/end: ______
```

### Checklist

| # | Area | Required procedure | Result |
|---|---|---|---|
| 3 | Clean install | `adb uninstall com.openmaps.app`; `.\gradlew.bat clean`; `assembleDebug`; install; confirm installed APK SHA matches local build | PARTIAL — installed via `adb install -r` (not a clean uninstall-first install); builds succeeded. Re-run with a true clean install before sign-off |
| 4 | First-launch permissions | Location prompt → precise → map centres; camera prompt on Live/Start → Live opens immediately; denied permission yields usable fallback | PARTIAL — location already granted from a prior install so the fresh-prompt flow was not observed; camera opened on Live entry with no prompt shown (already granted too). Re-run after a true uninstall |
| 5 | Explore / Map | 2D top-down default, location dot, recentre, POIs, category chips, no surprise pitch, acceptable perf | RUN — 2D default confirmed, location dot present, category chips row (incl. new Phase 12A categories once scrolled) rendered, Explore sheet opened and showed correct empty-state copy. Performance/scroll feel not specifically assessed |
| 6 | Search → Details → Directions → Preview | Multiple destinations; selection must **not** auto-start; verify line, destination, duration, distance, arrival, Start | PARTIAL — one destination ("Katy, Texas") reached Route Preview with correct distance/duration/arrival and the new Directions+Start block; did not test multiple destinations or verify auto-start is suppressed (destination selection happened outside direct observation — see §3a) |
| 7 | Start → Live | **≥10 repeats**: navigation active, Live auto-opens, camera opens, HUD appears, single session start, no duplicate route init, no Map/Live race | PARTIAL — 1 Start→Live transition plus 2 Map↔Live↔Map cycles, all clean, no crash. Far short of the required 10+ repeats |
| 8 | ARCore / Earth tracking | ARCore init, Earth tracking usable, anchors created, anchor count bounded, quality transitions handled; record time-to-first-usable-AR | RUN, blocked — see §3a's attempt table. Session creation and Geospatial-mode enablement both now succeed; Earth never reaches `ENABLED` because the Geospatial API is not authorized for this app (account provisioning, not a code defect) |
| 9 | AR arrow stability | Straight, curves, intersections, L/R turns, long segments: arrows ahead, correct direction, no flipping, behind-arrows removed, no duplicate sets, no anchor churn | NOT RUN — unreachable until item 8's blocker is resolved |
| 10 | Road alignment | Flag sidewalk/opposite-lane placement, large horizontal offset, arrows leaving the road, wrong turn geometry. Do not overfit one route | NOT RUN |
| 11 | Vertical / altitude | Flat, incline, decline, bridge/overpass: floating vs buried arrows, anchor jumps, altitude drift. Judge whether the fixed 0.35 m lift is acceptable or terrain-aware anchoring becomes a future requirement | NOT RUN |
| 12 | Map ↔ Live continuity | **≥20 transitions** during active nav: same route/destination/session/progress, no restart, no duplicate camera clients/anchors/arrows, no crash | PARTIAL — 2 transitions during active navigation, route/progress preserved both times, no crash. Far short of the required 20+ |
| 13 | Rerouting | Deliberately go off-route **more than once**: detection, request, old route legible meanwhile, replacement, old anchors+arrows cleared, new arrows appear, places + road intelligence refresh, camera stays active | NOT RUN |
| 14 | Arrival | Multiple short destinations: final maneuver clear, no bad overshoot, arrival state, arrows clear, clean end, camera stable, app does not close | NOT RUN |
| 15 | GPS drift | Lights, low speed, dense neighbourhood, open road: no progress oscillation, no constant arrow jumping, no repeated environment switching, no backward loop | NOT RUN |
| 16 | Environment modes | City / Highway / Suburb / Rural: record detected vs actual and confidence; confirm changes affect presentation only and never restart navigation | NOT RUN |
| 17 | Road Intelligence | Speed limit, current speed, weather, construction/hazards, alerts. **Do not mark success where no provider data exists.** Confirm missing data does not break nav, no false alerts, controlled clutter | NOT RUN |
| 18 | Speed validation | Compare in-app speed, speedometer, posted limit; document differences. GPS speed is not exact | NOT RUN |
| 19 | Places field test | Restaurants, parking, gas, EV: usefulness, map placement, Live label readability, clutter, Navigate-to-place still works | NOT RUN |
| 20 | Weak / lost network | Disable then restore data mid-route: route usable as far as architecture allows, camera and HUD stay up, no crash, recovery. Document what needs connectivity | NOT RUN |
| 21 | Background / foreground | Repeat multiple times during nav: navigation survives, camera resumes, AR reacquires, map state intact, no duplicate resources | NOT RUN |
| 22 | Screen lock / unlock | Lock and unlock during navigation: camera, navigation, route state and AR all recover | NOT RUN |
| 23 | Long session | ≥1 session of 30–60 min: memory, camera, AR, map, rerouting, environment transitions, network, battery, thermal | NOT RUN |
| 24 | Battery | Start %, end %, duration, brightness condition, whether Live active throughout | NOT RUN |
| 25 | Thermal | Device temperature, throttling warnings, camera/frame-rate/AR degradation during long sessions | NOT RUN |
| 26 | Performance | Jank, frame drops, slow Start→Live, camera/map/AR lag, ANRs. Capture logcat for `FATAL EXCEPTION`, `SIGSEGV`, `UnsatisfiedLinkError`, OOM, ANR, CameraX / ARCore / MapLibre native errors | NOT RUN |
| 27 | Failure recovery | Deny location; deny camera; AR unavailable; poor AR localization; route request failure; no network. Expect graceful fallback, usable map nav, clear message, no crash | NOT RUN |

## 5. Issues

| ID | Severity | Area | Summary | Status |
|---|---|---|---|---|
| E1 | — | Build env | `clean` fails on Windows when a Gradle daemon holds lint-cache jars | Open (workaround: `gradlew --stop` first) |
| E2 | — | Build env | `lintReportDebug` fails with the same handle contention | Open (same workaround) |
| F1 | P1 | AR | `ArCoreSpatialLocalization` constructed with the `Application` context; `findActivityOrNull()` can never resolve an `Activity` from it, so every real session-creation attempt failed before `Session.configure()` ever ran | **Fixed** in Phase 12A — Activity now resolved from the `LifecycleOwner` passed to `start()` |
| F2 | P1 | AR | ARCore Geospatial mode requires `com.google.android.gms:play-services-location` linked into the binary; it was not a declared dependency, so `Session.configure()` threw `GooglePlayServicesLocationLibraryNotLinkedException` | **Fixed** in Phase 12A — dependency added |
| F3 | P1 | AR (external) | No Google Cloud project has the ARCore Geospatial API enabled with this app's package name + signing certificates, so `Earth.earthState` is `ERROR_NOT_AUTHORIZED` | **Open — not a code defect.** Blocks AR arrows on every device until provisioned; see ROADMAP.md's Open questions |

**Three P1 defects were found this phase, two of them real application bugs — both fixed; the third
requires account-level provisioning outside the codebase.** This is the first phase since Phase 5
with a real device available, and finding a 100%-reproducible app bug this fast (two, in fact)
underscores how much emulator-only testing could not surface.

### Fixes applied in this phase

See F1/F2 above (`ArCoreSpatialLocalization.kt`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`), plus the Phase 12A changes documented in ROADMAP.md (nearby-category
expansion, Explore sheet, Route Preview action block, and the AR diagnostics overlay that is what
made F1–F3 discoverable one at a time instead of all reading as one bare "AR: Idle").

## 6. Unresolved limitations

1. **AR arrows remain unobserved**, now for one specific, external, identified reason (F3) rather
   than "no device available" — see ROADMAP.md's Open questions for exactly what unblocks it. Once
   provisioned, anchor stability, road alignment and altitude/lift are all still unverified against
   real driving (carried over from Phase 6/7/8 — the Phase 12A session was stationary/indoor).
2. **Environment modes other than `Unknown` have never been observed resolving against live map
   data** (carried over from Phase 11) — this remains a high-priority open item alongside AR.
3. **Road Intelligence has never been observed rendering a live alert.** Not re-tested in Phase 12A
   (the stationary indoor session had no route to evaluate hazards/weather/speed limits against).
4. The fixed **0.35 m arrow lift** remains unvalidated against real grades, declines and overpasses.
5. **Driving-dependent items are entirely untested**: rerouting, arrival, GPS drift while moving,
   background/foreground mid-trip, screen lock/unlock mid-trip, long session, battery, thermal,
   performance under motion — see the §4 checklist rows still marked `NOT RUN`.

## Exit criteria

| Criterion | Met |
|---|---|
| No reproducible P0 | No P0 found; three P1s found, two fixed, one external (F1–F3) |
| No dangerous known AR guidance defect | **Unproven** — AR arrows never rendered (blocked on F3) |
| Start → Live reliable | Partial — 1 transition clean on real hardware; needs the required 10+ repeats |
| Map ↔ Live reliable | Partial — 2 transitions clean on real hardware; needs the required 20+ |
| Rerouting reliable | **Unproven** — untested |
| Arrival reliable | **Unproven** — untested |
| Long-session stable | **Unproven** — untested |
| Major environment transitions acceptable | **Unproven** — untested |
| Battery / thermal behaviour understood | **Unproven** — untested |
| Known limitations documented | **Yes** — this report |

**Phase 12 exit criteria are still NOT met**, but real progress was made: two of the ten criteria
moved from "untested" to "partially verified on real hardware," and the AR criterion now has a
specific, named, external blocker (F3) instead of "no device available." A driving session is still
required for the remaining criteria.

## To unblock

1. **AR arrows specifically**: provision the ARCore Geospatial API (see ROADMAP.md's Open
   questions) — this is required regardless of which device is used next.
2. For the rest of the §4 checklist: take the same (or another) ARCore-certified device on an actual
   drive. `adb devices -l` already confirms a device works for this repo's tooling.
3. `.\gradlew.bat --stop` → `.\gradlew.bat clean` → `.\gradlew.bat assembleDebug`.
4. `adb uninstall com.openmaps.app`, then install `app/build/outputs/apk/debug/app-debug.apk` for a
   true clean-install pass (Phase 12A's session reused an existing install — see row 3/4 above).
5. Execute the §4 checklist and record results against SHA `0c1194a`.