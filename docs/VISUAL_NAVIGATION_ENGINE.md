# OpenMaps Android: Level 2 Visual Navigation Engine

Status: **Stage 1 prototype (calibration and projection) only.** Nothing in this document has been
validated on a physical device. Branch: `experimental/live-guidance-camerax-v1`, based on
`production/platform-v1` at `4da536e`.

Goal: road-aligned 3D chevrons that follow the real road, without ARCore Geospatial. Standard
turn-by-turn guidance stays available whenever alignment cannot be validated.

## 1. Audit of the existing code

Baseline choice: `production/platform-v1` is the only branch Android CI runs against and its last
check run is green. `main` is older. The 30 open PRs (#55 to #91) are stacked feature branches that
are not merged into it; none are touched or included here.

### Reusable as is (no ARCore imports)

| Area | Code | Use in the new engine |
|---|---|---|
| Routing | `core/routing` (`RouteResult`, `RouteManeuver`, Valhalla parser) | Route geometry and maneuvers. No routing-provider change. |
| Map matching | `core/navigation/RouteProgressTracker`, `OffRouteDetector`, `NavigationSession` | Position along the route, lateral distance, match confidence. Input to alignment and to the gate. |
| Navigation state | `feature/map/NavigationState`, `MapViewModel` | Single source of truth; Live only reads it. |
| Standard HUD | `feature/live/LiveHud`, `LiveHudState`, HUD composables in `LiveScreen` | The conventional fallback: maneuver arrow, distance, street/instruction, "Then", destination, remaining distance, ETA. Already works on the CameraX path. |
| Camera | `camera/CameraXRearEngine`, `RearCameraHost` | Preview. Extended with an optional companion use case. |
| Route sampling | `core/arrow/ARArrowSampler` | Samples points ahead along the route. Reusable for chevron spacing once alignment exists (it takes a `LocalizationQuality`, which needs a provider-neutral replacement). |
| Geometry | `core/spatial/GeoProjector.enuOffsetMeters` | Geographic to local east/north metres. |
| Chevron mesh | `arrow/ARArrowVertexGeometry` | Chevron vertex generation from screen-space arrows. |
| Voice, location, places, road alerts | `core/voice`, `core/location`, `core/roadintelligence` | Unchanged. |

### ARCore-dependent (preserved, not used by the prototype)

| Code | Dependency |
|---|---|
| `core/spatial/arcore/ArCoreSpatialLocalization` | ARCore session, Earth, Geospatial pose |
| `anchor/ArCoreAnchorBackend`, `core/anchor/RouteAnchorModels` | ARCore anchors |
| `core/spatial/SpatialLocalizationEngine` | Interface exposes ARCore `Frame` |
| `arrow/ARArrowPipeline`, `core/arrow/AnchoredArrowProjector`, `ARArrowProjector`, `ARArrowQualityGate` | Need a `SpatialPose` / anchors that only ARCore produces today |
| `arrow/ARArrowRenderer` | Draws inside ARCore's GL surface |
| `ArPreviewSurface` in `LiveScreen` | ARCore-owned camera preview |

### Gaps

- No camera intrinsics, frame timestamps or image access anywhere (preview only).
- No IMU use outside ARCore.
- No heading source besides GPS course (`UserLocation.bearingDegrees`), which is undefined when stopped.
- No recording or replay of sensor data.

## 2. Architecture

```
CameraX ──► CameraFrameProvider ──► RoadPerceptionEngine ──┐
              (frames, timestamps,   (road mask, lane lines,│
               intrinsics)            vanishing point)      │
                      │                                     ▼
                      └──► VisualMotionEstimator ──► PoseFusionEngine ◄── IMU, GPS
                                                         │   (attitude, yaw-to-route,
                                                         │    lateral offset, sigmas)
RouteResult + RouteProgress ───────────────► RouteAlignmentEngine ◄── RoadSurfaceModel
                                                         │
                                                         ▼
                                              GuidanceConfidenceGate
                                               │                 │
                                     RoadAnchored          ConventionalOnly
                                               ▼                 ▼
                                      RoadArrowRenderer    existing Live HUD
```

Interfaces are in `core/vision/VisualGuidanceModules.kt`. Rules:

- Route maneuvers, camera rendering, localization and confidence are separate modules with data-only
  boundaries, so each is testable on the JVM with recorded input.
- Modules that are not built have **no implementation at all**, not a stub that returns plausible
  values. The gate treats missing input as a refusal.
- The renderer is only ever called with a `GuidanceDecision.RoadAnchored`, which carries the evidence
  that justified it.
- The conventional HUD does not depend on any of this.

## 3. What Stage 1 implements

| Module | Code | State |
|---|---|---|
| CameraFrameProvider | `vision/CameraXFrameProvider` | Frame timestamp, size, rotation; lens focal length, sensor size, sensor orientation. Reads no pixels. |
| Calibration model | `core/vision/CameraCalibration.kt` | Pinhole intrinsics from sensor geometry; upright rotation; mount configuration. |
| Attitude | `vision/DeviceAttitudeSource`, `core/vision/CameraAttitude` | Pitch and roll from the game rotation vector. No heading. |
| Projection | `core/vision/GroundPlaneProjector`, `FillCenterTransform` | Flat-ground point to image pixel to view pixel. |
| Test geometry | `core/vision/CalibrationGrid`, `feature/live/LiveCalibrationOverlay` | Dashed ground grid, horizon line, numeric readout. Labelled as a test pattern. |
| GuidanceConfidenceGate | `core/vision/GuidanceConfidenceGate` | Fail-closed decision with named reasons. Always "standard guidance only" in Stage 1. |
| Perception, motion, fusion, alignment, surface, arrow renderer | interfaces only | Not implemented. |

Build switch: `BuildConfig.LIVE_VISUAL_PROTOTYPE` is `true` in debug builds and `false` in release
builds on this branch. When true, Live uses CameraX and never starts ARCore, and the calibration
overlay is drawn. The ARCore classes are unchanged.

The calibration grid is centred on the camera and assumes level ground. It does not detect or follow
the road. It exists to check the projection chain on a real phone.

## 4. Computer-vision approach (proposal, nothing benchmarked)

The quantity that matters most is **camera yaw relative to the road** and **lateral position on the
road**. A 2 degree yaw error moves a chevron 0.7 m sideways at 20 m and 1.4 m at 40 m. GPS and the
compass cannot supply that; the image can.

Proposed sequence:

1. **Classical baseline (OpenCV).** Edge detection and line fitting for lane markings and road edges,
   vanishing-point estimation, inverse-perspective mapping with the Stage 1 projector. Cheap, no model,
   and gives yaw and pitch relative to the road on straight, marked roads. Expected to fail on
   unmarked roads, at night, in rain, and in heavy traffic; that failure rate must be measured.
2. **Learned drivable-area and lane segmentation**, evaluated against the baseline. Candidates:
   - TwinLiteNet / TwinLiteNet+ (very small, drivable area + lane lines)
   - YOLOP, YOLOPv2, HybridNets (multi-task, heavier)
   - Ultra-Fast-Lane-Detection v2 (lane lines only)
   - A general real-time segmenter (BiSeNetV2, PIDNet, DDRNet) trained on a driving dataset
   Runtime candidates: LiteRT (TensorFlow Lite) with GPU delegate, or ONNX Runtime Mobile.
   **Not verified:** accuracy on Houston-area roads, speed on the test phone, and the licences of the
   weights and of the datasets they were trained on. Licences must be cleared before anything ships.
3. **Visual motion.** Gyroscope integration for frame-to-frame rotation; sparse optical flow
   (OpenCV KLT) with RANSAC to correct gyro drift and detect camera movement in the mount.
4. **Fusion.** A small filter over yaw-to-route, pitch, roll, lateral offset and camera height.
   Predict with gyro; correct with vanishing point, road edges, map-matched position and route
   bearing. Every output carries a sigma.
5. **Alignment.** Fit the upcoming route polyline (in the levelled camera frame) to the observed road
   boundary; the residual in metres is the alignment quality.

Lane markings are not lane identity. Seeing two lines does not say which lane of four the vehicle is
in; no lane recommendation is derived from this engine.

## 5. Calibration and synchronization requirements

- One phone model and one mount position for the prototype. Record model, Android version, lens
  used, mount height (tape measure, lens to road) and whether the phone sits behind tinted glass.
- Intrinsics: Stage 1 derives them from reported focal length and sensor size, which is not a
  calibration. Before Stage 4, measure them with a checkerboard for the test phone and compare.
- Lock zoom at 1x, disable video stabilisation, keep one physical lens. Any of these changing
  invalidates the intrinsics.
- Preview and analysis streams must share aspect ratio and field of view (both requested at 16:9).
- Timestamps: camera frame time and sensor event time are both nanoseconds since boot on devices
  whose camera reports `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`. Where it reports `UNKNOWN` the offset
  must be estimated; the gate treats unsynchronised attitude as stale.
- GPS fixes arrive roughly once a second and are already 100 ms or more old. At 30 m/s that is
  several metres along the road. Location must be propagated to frame time with speed, and
  longitudinal uncertainty carried into where a turn is drawn.

## 6. Risks and failure modes

| Risk | Effect | Mitigation |
|---|---|---|
| Wrong intrinsics (EIS crop, lens switch, zoom) | Everything projects at the wrong scale | Lock camera settings; verify with the grid; measure intrinsics |
| Mount height or pitch wrong, or phone moves in the mount | Grid and arrows float above or sink below the road | Measured height; detect mount movement; gate closes |
| Accelerometer bias while braking/cornering | Pitch and roll off by degrees | Fuse gyro and vision; gate on attitude consistency |
| Road is not flat (hills, crowns, ramps) | Flat-plane projection diverges with distance | `RoadSurfaceModel`; shorten arrow range when vertical curvature is detected |
| **False road alignment** (parallel road, frontage road, shadows, tar seams, trucks read as edges) | Arrows drawn confidently on the wrong surface | Require agreement of perception, route shape and map match; temporal consistency; residual threshold; hide instead of guessing |
| GPS latency and error along the road | Turn drawn early or late | Draw turn chevrons only when the turn is visually confirmed; otherwise banner only |
| Night, rain, glare, dirty windshield | Perception confidence collapses | Gate closes; standard HUD |
| Heat and battery | Throttling, frame drops | Measure; cap resolution and frame rate |
| Attractive but wrong overlay | Driver trusts it | No road-anchored graphics without an open gate; test pattern is dashed and labelled |

## 7. Implementation plan and acceptance criteria

Each stage needs its criteria met on the physical test phone before the next starts. Thresholds
marked (provisional) must be confirmed with recordings.

| Stage | Deliverable | Acceptance |
|---|---|---|
| 1. Calibration and projection (this PR) | CameraX feed, intrinsics, attitude, grid, gate | CI green. On device: camera starts 20/20 Live entries; horizon line within 1 degree of the real horizon on level ground; 10 m cross line within 0.5 m of a tape-measured 10 m mark; grid stays glued while the phone is rolled and pitched; frame rate reported at 25 fps or more; gate always reports standard guidance |
| 1b. Recorder | Timestamped video + IMU + GPS + route log to disk; JVM replay harness | A recorded drive replays deterministically through the projection code |
| 2. Road perception | OpenCV baseline, then model candidates | On labelled recordings: road-edge error under 0.3 m at 10 to 20 m (provisional); false-road rate reported per condition; 15 fps or more; battery and temperature logged over 30 minutes |
| 3. Fusion and alignment | Pose filter, route alignment, real gate inputs | Yaw-to-road error under 2 degrees for 95% of gated frames (provisional); gate closes within 0.5 s of injected faults in replay |
| 4. 3D chevrons | `RoadArrowRenderer` on validated geometry | Projection error under 0.5 m at 20 m for 95% of displayed frames (provisional); no displayed frame with alignment residual over threshold; flicker (gate toggles) under 1 per 10 s |
| 5. Comparison and validation | Report against ARCore build | See section 8; safe physical-device drive on city, suburb, highway |

## 8. Comparison against the ARCore implementation

Run both builds on the same phone, mount, route and time of day. PR #91 is the ARCore reference.

| Metric | How it is measured |
|---|---|
| Camera startup success | Live entries with preview within 3 s / total, from the reliability log |
| Frame stability | Frame interval mean and 95th percentile |
| Guidance availability | Share of navigation time with a maneuver banner; share with road-anchored arrows |
| Arrow rendering | Frames with arrows drawn / frames where the gate (or ARCore quality gate) allowed them |
| Road alignment accuracy | Projection error against labelled road edges in recordings |
| GPS and heading uncertainty | Logged accuracy and sigma distributions |
| Battery | Percent per hour, screen brightness fixed |
| Device compatibility | Works without Google Play Services for AR and without an API key |
| Recovery | Time to camera after app background/foreground, camera stolen by another app, GPS loss |

Recorded or synthetic traces make the logic reproducible. They do not replace a drive.

## 9. Device test procedure (Stage 1)

Safety: do every step parked, or as a passenger. Do not look at or handle the phone while driving.

1. Install the debug APK from this PR's CI run (artifact `openmaps-debug-apk`) or build
   `assembleDebug` locally. No ARCore API key is needed.
2. Write down: phone model, Android version, mount position, lens height above the road in metres.
3. Open Live. Expect the camera preview, the normal Live HUD, a dashed grid, an orange dashed
   horizon line and a readout panel titled "CALIBRATION TEST PATTERN".
4. Screenshot the readout. Check that frame size, rotation, fps, FOV, pitch and roll are present and
   that the gate line says "standard guidance only".
5. Park on level ground facing a visible horizon or a long straight road. The orange line should sit
   on the horizon. Screenshot.
6. Tilt the phone down and up, then roll it left and right. The grid and horizon should stay fixed to
   the world, not to the screen. If they move the wrong way, note which direction.
7. Place a marker 10 m ahead of the lens on flat ground (tape measure). The second cross line (10 m)
   should fall on it once the mount height in `MountConfiguration` matches your measurement.
8. Rotate the phone to landscape if you use it that way and repeat steps 5 and 6.
9. Start a route and confirm the standard HUD works on CameraX: maneuver arrow and distance,
   instruction or street, "Then" card, remaining distance, ETA.
10. Background the app for 10 s and return; open another camera app and return. The preview should
    recover each time.
11. Report results with the screenshots. Any mismatch is a calibration bug to fix before Stage 2.

## 10. Limits of this prototype

- No road detection, no road-anchored arrows, no lane guidance.
- Camera yaw relative to the road is unknown.
- Intrinsics are derived, not measured; lens distortion is ignored.
- Mount height is a placeholder until measured.
- Sign conventions for pitch and roll are unit-tested against the Android sensor definition but have
  not been seen on a device.
