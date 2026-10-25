# OpenMaps Android Auto — host foundation (developer only)

**State:** Initial host discovery / Car App Library handshake **only**. This is
NOT functional turn-by-turn navigation, a finished MapLibre map surface, an
incident reporting flow, or an approved Play Store car app.

**Scope:** Android Auto phone-projected head units. Not Android Automotive OS
native distribution, OEM camera integration, car camera AR, or windshield/HUD.

## Implementation

- Car App Library `app` and `app-projected` dependencies pinned to
  `1.8.0-rc01` (contains August 2026 security fix; verify stable version before release).
- Manifest advertises `androidx.car.app.category.NAVIGATION` and declares
  `NAVIGATION_TEMPLATES` and `ACCESS_SURFACE` permissions.
- `OpenMapsCarAppService` creates a Car App Session with a **non-navigating**
  `PaneTemplate` stating that map navigation is not enabled.
- `androidx.car.app.validation.HostValidator` allows development hosts only
  in debug builds. Release uses Google's sample signature allowlist as a
  defensive secondary control.
- **Release service is disabled** via `res/values/bools.xml`;
  `src/debug/res/values/bools.xml` enables it only for developer testing.

## Developer tests

1. Build a debug APK from this dedicated branch:
   `./gradlew :app:assembleDebug`.
2. Install on a test phone with Android Auto, connect to Google's Desktop
   Head Unit (DHU) in developer mode. Confirm the OpenMaps car entry appears.
3. Open OpenMaps on the DHU: verify the *development-only* connection screen,
   no simulated route/direction, no camera, no crash.
4. Stop/restart the car session, disconnect/reconnect phone, check host
   validation, and confirm no second GPS/navigation service starts.
5. Build a release bundle (`./gradlew :app:bundleRelease`): inspect merged
   manifest/resources to prove the CarAppService stays **disabled**.
6. Do not submit this prototype as an Android Auto navigation app to Play.

## Next phase — **not implemented in this PR**

1. Prototype real MapLibre map drawing onto the `SurfaceContainer` via
   `AppManager.setSurfaceCallback`, respect surface dimensions, visible
   and stable areas, map camera, host dark/light mode and lifecycle.
2. Use `MapWithContentTemplate` for search/route preview and
   `NavigationTemplate` for active navigation. Reuse existing
   `MapViewModel` route progress and HERE/Valhalla backend rather than
   create a second trip engine.
3. Design shared route ownership, turn-by-turn, ETA, voice and stop
   navigation actions; ensure car and phone remain synchronized.
4. Add limited, car-safe incident reporting through the existing
   `BackendCommunityReportsRepository`; respect template and driver
   distraction limits, including disabled interaction when required.
5. Test with DHU and a real Android Auto vehicle parked, then Google
   Play automotive quality review. Issue #80's **route-instruction
   safety bugs** must be resolved before driver-facing use.

Official resources:
- https://developer.android.com/training/cars/apps/auto
- https://developer.android.com/training/cars/apps/navigation
- https://developer.android.com/training/cars/apps/library/draw-maps
- https://developer.android.com/training/cars/testing/dhu
- https://developer.android.com/jetpack/androidx/releases/car-app

Tracking: https://github.com/Dr-Opara/openmaps-android/issues/83
