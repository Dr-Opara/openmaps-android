# OpenMaps Android: Level 2 Visual Navigation Engine

Status: **Stage 1 (calibration and projection) plus Stage 1b (recorder and replay).** Nothing in this document has been
validated on a physical device. Branch: `experimental/live-guidance-camerax-v1`, based on
`production/platform-v1` at `4da536e`. Draft PR #92.

Primary test device: **Samsung Galaxy S26 Ultra, model SM-S948U1**, windshield mount, portrait, main
rear camera. Design target: the original OpenMaps road-anchored chevrons (`ARArrowVertexGeometry`,
the concept images). The standard Live HUD is the fallback, not the product.

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

## 3. What is implemented

| Module | Code | State |
|---|---|---|
| CameraFrameProvider | `vision/CameraXFrameProvider` | Selects the main rear camera explicitly; frame timestamp, size, rotation; all published lens/sensor metadata; optional frame sink. |
| Calibration model | `core/vision/CameraCalibration.kt` | Pinhole intrinsics, reported or estimated; upright rotation. |
| Calibration profile | `core/vision/CalibrationProfile.kt`, `vision/CalibrationProfileStore` | Per phone model, camera id and display rotation; persisted on the device. |
| Mount drift | `core/vision/MountDriftDetector` | Latches "moved" when pitch or roll stays 4 degrees off the captured mount angle for 20 s. |
| Attitude | `vision/DeviceAttitudeSource`, `core/vision/CameraAttitude`, `RotationVector` | Pitch and roll from the game rotation vector, in plain Kotlin shared with replay. No heading. |
| Projection | `core/vision/GroundPlaneProjector`, `FillCenterTransform` | Flat-ground point to image pixel to view pixel. |
| Test pattern | `core/vision/CalibrationGrid`, `feature/live/LiveVisualPrototypeOverlay` | Horizon always; ground grid only after a lens height is entered. |
| Recorder | `vision/recording/SessionRecorder`, `RecordingStore`, `core/vision/recording/SessionWriter` | Frames, IMU, GPS, route, progress, calibration. Consent, indicator, delete. |
| Replay | `core/vision/recording/RecordedSession`, `ClockAlignment`, `AttitudeReplay` | Loads a session and replays it in boot-clock order on the JVM. |
| GuidanceConfidenceGate | `core/vision/GuidanceConfidenceGate` | Fail-closed with named reasons. Always "standard guidance only" at this stage. |
| Perception, motion, fusion, alignment, surface, arrow renderer | interfaces only | Not implemented. |

Build switch: `BuildConfig.LIVE_VISUAL_PROTOTYPE` is `true` in debug builds and `false` in release
builds on this branch. When true, Live uses CameraX and never starts ARCore. The ARCore classes are
unchanged and Level 2 has no dependency on them.

## 4. Camera selection, intrinsics and calibration profile

### Which camera

CameraX's default back camera is normally the main wide camera, by convention only. The prototype
selects the back-facing camera with the lowest numeric Camera2 id and shows the id, physical lens
ids, focal length and field of view in the overlay. On the S26 Ultra this is expected to be the main
camera but **has not been confirmed on the device**. A main wide camera should show a field of view
of roughly 60 to 85 degrees along the long side of the frame; an ultra-wide is above 100 and a
telephoto below 40. If the readout shows the wrong lens, report the id and FOV.

### Intrinsic metadata: what is measured and what is not

| Source | Label in the overlay | What it is |
|---|---|---|
| `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` + `SENSOR_INFO_PHYSICAL_SIZE` | ESTIMATED from lens+sensor | Assumes square pixels, centred principal point, no distortion. Always available. |
| `LENS_INTRINSIC_CALIBRATION` | REPORTED by device | The manufacturer's fx, fy, cx, cy for the module. Used when present. Not a measurement of this phone. |
| `LENS_DISTORTION` | "distortion: yes (not applied)" | Recorded with every session. Not yet used in projection. |
| Checkerboard calibration of the test phone | MEASURED | Not done. Required before Stage 4. |

Both reported and estimated values are scaled to the output buffer assuming it is a centred
aspect-ratio crop of the sensor at 1x. When both exist the overlay shows how far they disagree. Whether
the S26 Ultra publishes `LENS_INTRINSIC_CALIBRATION` and `LENS_DISTORTION` is unknown until the
readout is seen on the phone. The gate treats anything other than MEASURED as unverified until a
tester confirms the grid against a tape-measured mark.

### Transforms accounted for

Sensor orientation and display rotation (CameraX `rotationDegrees`, applied to the intrinsics);
display rotation in the roll calculation; preview `FILL_CENTER` cropping; analysis and preview both at
16:9. Not accounted for: lens distortion, EIS crop, zoom other than 1x.

### Profile

One profile per phone model + camera id + display rotation, stored in app-private preferences:

- **Lens height (m):** typed in from a tape measure. There is no default. Until it is entered the
  ground grid is not drawn; only the horizon is.
- **Pitch trim / roll trim (degrees):** manual corrections in 0.1 degree steps so the orange line
  sits on the real horizon.
- **Mount angle:** the sensor pitch and roll captured while parked on level ground. This is the
  reference for drift detection.
- **Grid check confirmed:** a tester's statement that the grid matched a measured mark. Cannot be set
  without a height and a mount angle.

Recalibration: if smoothed pitch or roll stays more than 4 degrees from the captured mount angle for
20 seconds, the profile is flagged MOUNT MOVED, the grid is hidden and the gate reports `MountMoved`
until the profile is saved again. Rotating the phone to landscape selects a different (empty)
profile. A long steep hill can also trip the flag; that is deliberate.

## 5. Recording and replay

### What is recorded

`Android/data/com.openmaps.app/files/vision-recordings/<yyyyMMdd-HHmmss>/`

| File | Content |
|---|---|
| `session.properties` | Phone model, Android version, app version, camera id and all camera metadata, intrinsics used and their source, calibration profile, clock information, display rotation |
| `frames.csv` + `frames/NNNNNN.jpg` | About 10 frames per second at 1280x720, saved as delivered (unrotated); per frame: camera timestamp, arrival time, size, rotation |
| `imu.csv` | Accelerometer, gyroscope, game rotation vector, rotation vector, magnetometer at 100 Hz requested |
| `location.csv` | Raw GPS fixes: position, accuracy, bearing and its accuracy, speed and its accuracy, altitude |
| `route.csv`, `progress.csv` | Active route geometry (per route version) and map-matched progress |

Recording stops by itself after 20 minutes, when free space falls below 500 MB, or when Live is left.

### Clocks

Everything is in nanoseconds on monotonic clocks; wall-clock time is stored once, for reference only.

- IMU: `SensorEvent.timestamp`, the boot clock (`elapsedRealtimeNanos`).
- GPS: `Location.elapsedRealtimeNanos`, the boot clock. This is when the fix was computed, not when
  it was delivered.
- Camera: the sensor timestamp, **plus** the boot-clock time the frame reached the app. If the camera
  declares `TIMESTAMP_SOURCE_REALTIME` its timestamps are already on the boot clock and are used
  as is. If it does not, replay looks at (arrival - timestamp): when the smallest value is between 0
  and 500 ms the clocks are assumed to be the same; otherwise that smallest value is used as the
  offset, which leaves frame times late by the camera's minimum pipeline latency (unknown, typically
  tens of milliseconds). The session reports which of the three cases applied.

### Replay

`RecordedSession.read(dir)` loads a session; `replay { event -> }` delivers frames, IMU, GPS and
progress in boot-clock order, synchronously, with fixed tie-breaking, so two runs are identical.
`AttitudeReplay` rebuilds camera attitude with the same code the live overlay uses. Damaged rows are
skipped and counted. Frame pixels are JPEG files, decoded by whatever consumes them.

### Privacy

- Recording is off until the tester accepts an explicit consent dialog that lists what is captured.
- A red REC badge with elapsed time and frame count is on screen whenever recording is active.
- Files are in app-private storage, are not backed up (`allowBackup="false"`), and are removed on
  uninstall. The recorder has no network code; nothing is uploaded.
- "Files" lists recordings with sizes, deletes one or all, and withdraws consent.
- Frames can show other people, vehicles and number plates. Treat copies accordingly.

## 6a. Road model evaluation (desk review only, nothing integrated or benchmarked by us)

| Candidate | Code licence | Weights trained on | Commercial use of published weights | Android |
|---|---|---|---|---|
| TwinLiteNet (drivable area + lane lines, 360x640 input, ~3 MB) | MIT | BDD100K | **Not cleared.** BDD100K's data licence grants free use for education, research and not-for-profit purposes; commercial rights are reserved to BDD/BAIR members, others must license from UC Berkeley. | A LiteRT conversion is published by the LiteRT community |
| YOLOP / YOLOPv2 / HybridNets | Not verified in this review | BDD100K | Same BDD100K issue | Heavier; not checked |
| Ultra-Fast-Lane-Detection v2 | Not verified | CULane / TuSimple | Dataset terms not verified | Not checked |
| Small segmenter trained by us on comma10k + our own recordings | Architecture dependent (TwinLiteNet's is MIT) | comma10k: MIT, "no academic only restrictions"; 10,000 labelled driving images with road and lane-marking classes | **Clear, subject to legal confirmation** | LiteRT export under our control |

Reported, not measured by us: the LiteRT community page for TwinLiteNet lists about 14 ms per frame
on a "Galaxy S26" (Hexagon NPU and Adreno GPU), about 44 ms on a Pixel 8a GPU and about 700 ms on a
Pixel 8a CPU. The S26 Ultra figure must be measured on our phone.

Conclusion so far: TwinLiteNet's published weights are suitable for **internal evaluation on
recordings only**. Shipping needs either a BDD100K commercial licence or weights trained on
permissively licensed data (comma10k plus our own labelled recordings). This is an engineering reading
of the licences, not legal advice.

Sources: [TwinLiteNet repository](https://github.com/chequanghuy/TwinLiteNet),
[TwinLiteNet LiteRT model card](https://huggingface.co/litert-community/TwinLiteNet-LiteRT),
[BDD100K licence text as mirrored](https://huggingface.co/datasets/dgural/bdd100k),
[comma10k](https://www.github.com/commaai/comma10k).

## 6b. Computer-vision approach (proposal, nothing benchmarked)

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
| 1. Calibration and projection (this PR) | CameraX feed, intrinsics, attitude, profile, grid, gate | CI green. On device: camera starts 20/20 Live entries; horizon line within 1 degree of the real horizon on level ground; 10 m cross line within 0.5 m of a tape-measured 10 m mark; grid stays glued while the phone is rolled and pitched; frame rate reported at 25 fps or more; gate always reports standard guidance |
| 1b. Recorder (this PR) | Timestamped frames + IMU + GPS + route log to disk; JVM replay | CI green. On device: a 5-minute recording has frames at 9 fps or more, IMU at 90 Hz or more, GPS fixes present, and replays on the JVM with zero skipped rows |
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

## 9. Install and test (S26 Ultra)

Safety: do every step parked, or as a passenger. Do not handle the phone while driving.

### Install

1. On a computer, open PR #92 on GitHub, open the latest "Android CI" run, and download the
   artifact **`openmaps-debug-apk`** (a zip). Unzip it to get `app-debug.apk`.
2. Copy the APK to the phone (USB, or download it on the phone directly while signed in to GitHub).
3. On the phone: Settings > Apps > Special access > Install unknown apps, allow the app you are
   opening the APK with (My Files or Chrome).
4. If an OpenMaps build signed with a different key is installed, Android will refuse the update.
   Uninstall the existing OpenMaps first (this deletes its saved data), then install.
5. Open the APK and install. Grant camera and location permission when Live asks.

With a computer and USB debugging instead: `adb install -r app-debug.apk`.

### Calibrate

1. Mount the phone on the windshield in portrait, where it will stay.
2. Tape-measure from the road surface to the centre of the main rear lens. Note it in metres.
3. Park on level ground. Open OpenMaps, switch to Live. A panel titled "CALIBRATION TEST PATTERN"
   appears at the left with an orange dashed horizon line. No grid yet.
4. Screenshot the panel. It shows the camera id, FOV, whether intrinsics are REPORTED or ESTIMATED,
   whether distortion data exists, the frame clock, frame rate and camera latency.
5. Tap **Calibrate**. Enter the lens height. Tap **Capture mount angle now**. Save.
6. The grid appears. If the orange line is above or below the real horizon, reopen Calibrate and
   adjust pitch trim (and roll trim if it is tilted) in 0.1 degree steps. Save.
7. Place a marker on flat ground 10 m ahead of the lens (tape measure). The second cross line is
   10 m. Note how far off it is.
8. Tilt and roll the phone slightly in the mount and let go: the pattern should stay fixed to the
   world. Then deliberately re-aim the phone by more than 5 degrees and wait 20 s: the panel should
   read MOUNT MOVED and hide the grid. Re-aim, Calibrate, capture again, Save.
9. Only if step 7 matched within 0.5 m: tick "I checked the grid..." and Save.

### Record

1. Tap **Record**. Read the consent text. Agree to start.
2. Confirm the red REC badge counts up. Start a route and drive (passenger operates the phone).
3. Tap **Stop**. Open **Files** to see the session and its size. Delete from there.
4. To copy a session off for replay: USB, then
   `adb pull /sdcard/Android/data/com.openmaps.app/files/vision-recordings/`.

### Report back

Screenshots from Calibrate steps 4, 6 and 7; the lens height; whether steps 7 and 8 behaved as
described; frame rate while recording and the "write ms" figure; and one short recording if you are
comfortable sharing it.

## 10. Limits

- No road detection, no road-anchored chevrons, no lane guidance. No claim of alignment or accuracy.
- Camera yaw relative to the road is unknown.
- No measured intrinsics; distortion metadata is recorded but not applied.
- Camera selection, sign conventions, metadata availability and timestamp source are untested on the
  S26 Ultra.
- Frame rate, recorder cost, model inference latency and rendering cost have not been measured on the
  device. No model is integrated, so there is no inference latency to report yet.
- Recording saves JPEG frames at about 10 fps, not continuous video.
- The recorder has not run on a device; JVM tests cover the file format, clock alignment and replay.
