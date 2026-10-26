# ARCore Geospatial gate diagnosis — Samsung field acceptance

**Status:** Developer-only instrumentation. **Not** a fix for ARCore authorization,
camera tracking, GPS/VPS convergence, or route-arrow placement. Production UI unchanged.

## Why

Samsung road testing on OpenMaps Live showed `TrackingNotReady · sampled 0 · drawn 0`
and, in separate tests, `CameraXFallback` or `GeospatialAccuracyInsufficient`.
Those are not interchangeable. A green `:app:verifyProductionArCoreConfiguration`
only checks that a plausible configuration is present; it does not prove that the
installed APK has been authorized by Google or that ARCore has an Earth pose.

`ArCoreSpatialLocalization` now emits **DEBUG builds only** `geospatialGate` records
when the camera/Earth/quality gate changes and at most once every 10 seconds when
unchanged. Fields:

- `camera` and `cameraFailure`: is camera tracking, paused or stopped?
- `earth` and `earthTracking`: Earth authorization and independent tracking state.
- `localization`: current provider-neutral state and reason.
- `posePresent`: Geospatial pose obtained?
- `horizontalAccuracyM` and `yawAccuracyDeg`: confidence measures,
  **not** user location or heading.

No latitude, longitude, altitude, device heading, route geometry, photos or
API keys are logged by this change. Logcat may contain other app/system information:
**share only the `geospatialGate` lines, redacting any other sensitive text.**

## Controlled test on a real device

1. Build this draft diagnostic branch (separate from other dirty worktrees) with
   both your **unchanged ARCore** credential and normal navigation config.
   Use `assembleDebug`; install the APK on a supported Samsung. Verify it is
   the same debug build you just compiled, with the correct package/signature.
2. Ensure Google Play Services for AR is up to date, **precise** location and
   camera permissions are granted, internet access works, and the rear camera
   is not in use by another app.
3. With the phone safely **parked/stationary outdoors in daylight**, navigate to
   a nearby valid route, select **Live**, and point the phone at the road
   environment from a safe position. Do not operate the phone while driving.
4. In Android Studio's Terminal:

   ```powershell
   adb logcat -c
   adb logcat -v time -s ArCoreSpatialLoc:D
   ```

   If `adb` is not in PATH, use Android Studio's SDK `platform-tools/adb`
   or **Logcat** window filtered by `ArCoreSpatialLoc`.
5. Note the **Live debug overlay** diagnosis: `CameraXFallback`,
   `TrackingNotReady`, `GeospatialAccuracyInsufficient`,
   `sampled N`, `drawn N`; then look at the `geospatialGate` lines.
   Capture a screenshot in a safe, parked setting, not while driving.
6. Repeat in a second well-lit outdoor location if Earth fails to track.
   If uncertainty persists, check VPS availability using Google's Geospatial API
   or compare with Google's ARCore Geospatial sample app at the **same** safe site.

## Decision guide

| Evidence | First suspect | Next action |
|---|---|---|
| `CameraXFallback`, no `geospatialGate` records | ARCore provider/availability selection | Inspect `probeAvailability`, installed AR services, CameraX fallback reason |
| `earth=ERROR_NOT_AUTHORIZED` | Google ARCore credential authorization | Compare *installed APK* signature/package to Cloud restrictions, verify ARCore API; never print key |
| `earth=ERROR_RESOURCE_EXHAUSTED` | Quotas/rate limiting | Inspect ARCore API quotas, retry later without loops |
| `camera=PAUSED` / `cameraFailure=INSUFFICIENT_LIGHT` | Camera tracking quality | Retry stationary in daylight |
| `earth=ENABLED`, `earthTracking=PAUSED` | Earth localization still converging | Check GPS/VPS coverage/permissions; wait stationary |
| `posePresent=true`, quality Poor/Acquiring | Horizontal/yaw uncertainty | Investigate recorded *accuracy only* and safety gate; never relax threshold solely for appearance |
| Good Earth pose, `sampled 0` | AR route-progress/quality gate/sampler | Instrument ARArrowPipeline suppression reason and geometry |
| `sampled > 0`, `drawn 0` | Projection/GL/anchors | Inspect projector, GLSurfaceView renderer and overlay lifecycle |

**Safety:** Never force fake screen-space arrows, loosen accuracy thresholds just
to render, or declare AR aligned solely because the HUD shows a white maneuver
arrow. Both correct routing maneuvers and safe spatial localization must pass
before real-road Live AR acceptance.

## References

- Google's ARCore Geospatial overview: https://developers.google.com/ar/develop/java/geospatial/enable
- VPS availability: https://developers.google.com/ar/develop/java/geospatial/check-vps-availability
- Earth state reference: https://developers.google.com/ar/reference/java/com/google/ar/core/Earth.EarthState
- Current release-blocker issue: https://github.com/Dr-Opara/openmaps-android/issues/80

## Failure classification and recovery (added on `fix/map-smooth-follow-snapped-puck-v1`)

AR failures are classified by `ArFailurePolicy` instead of "any failure permanently switches to CameraX":

| Cause | Class | Behaviour |
|---|---|---|
| `ERROR_NOT_AUTHORIZED`, Geospatial disabled/unsupported | Permanent configuration | Immediate CameraX fallback; `Log.e` (all build types) says what to check; reliability event `...:permanent_configuration`; not retried on Live re-entry |
| Camera lost, ARCore frame error, `ERROR_INTERNAL`, quota | Recoverable | Session rebuilt with 1s/2s/4s backoff (10s/20s/40s for quota) while AR keeps the camera; fallback only after 3 failed attempts; a fresh attempt on the next Live entry |
| Session paused / frame deadline / not-yet-available | Transient | Frame skipped, nothing reported |

**Builds without a Geospatial key:** the CI workflow (`android-ci.yml`) does not pass `ARCORE_API_KEY`, so APKs
from CI have an empty `com.google.android.ar.API_KEY` and Earth will report `ERROR_NOT_AUTHORIZED`
(logged as "this build has no ARCORE_API_KEY"). Use a locally built APK with the key in `local.properties`, or add
the key as a CI secret restricted to this package name and signing SHA-1 (note CI debug artifacts are downloadable).
The log line says whether a key was compiled in; it never prints the key.
