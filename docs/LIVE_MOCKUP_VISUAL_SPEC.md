# Live / Route Preview — Reference-to-Component Visual Spec (Phase 1)

Branch: `feature/live-mockup-visual-fidelity-v1`, off `feature/live-original-reference-exact-v1` @ `1e9e8c3`.
Scope: visual fidelity only. No changes to `MapViewModel`, `NavigationState`, `ARArrowPipeline`, route/anchor
lifecycle, or the OpenGL pipeline. Does not touch `LiveScreen.kt` (Muse's active file) or `MapControls.kt`'s
wired composables — findings below are the basis for new, isolated components, integrated in a later phase.

**Reference set**: one 3-panel composite (night urban Live, day residential Live, Route Preview) plus five
additional full-resolution frames (dusk urban, two highway variants, day residential, rural). All six share
one design language and are treated as a single reference set; where two frames disagree on a chrome detail,
that is called out explicitly rather than silently picking one.

**Evidence caveat**: all verdicts below come from reading the reference images against the actual Kotlin
source (file/line cited per item), not from a rendered build — no Android emulator/device was available in
this environment (`adb` not on PATH). Nothing here is a claim of pixel-exact or on-device fidelity; several
items (chevron glow intensity, speed-arc fraction) are flagged below as explicitly needing an on-device
screenshot before further changes.

---

## 1. Already matches current code (no action needed)

| Component | Reference | Code | File |
|---|---|---|---|
| Live header | OpenMaps wordmark top-left + Live\|Map toggle top-right, visible during active navigation in every reference frame | `LiveTopBar` is composed unconditionally (not gated on `hudState`/`isNavigating`) | `LiveScreen.kt:219-222`, `:1220` |
| Maneuver card | Dark glass rounded card, blue rounded-square icon tile, big bold distance + instruction below | `LiveManeuverHud`: `RoundedCornerShape(26.dp)`, `Color(0xE60B1928)`, 72dp tile `RoundedCornerShape(19.dp)` blue fill, headlineMedium + titleMedium | `LiveScreen.kt:1306-1347` |
| Street-name tag | Green pill, e.g. "7th St NW" | `LiveStreetNameTag` uses `OpenMapsColors.Success` fill | `LiveScreen.kt:690-702` |
| POI context chips | Dark pill, one line, leader stem + dot down to the real anchor point | `LivePlaceChip` + `LivePlaceMarker` (stem/dot already implemented) | `LiveScreen.kt:961-980`, `:1037-1059` |
| Hazard/traffic alert pill | Translucent amber fill, amber border, amber text | `LiveAlertPill` | `LiveScreen.kt:659-683` |
| Speed-limit sign | White card, dark border, "SPEED LIMIT" caption + big number, MUTCD-style | `LiveSpeedBlock`'s limit `Column` | `LiveScreen.kt:575-596` |
| Bottom ETA bar structure | 3 equal-weight stats (duration/distance/arrival), optional status line with colored dot | `LiveTripSummary` | `LiveScreen.kt:1452-1519` |
| AR chevron shape family | Bold, hollow/open double-arm chevrons (not filled triangles), two-pass glow+core | `ARArrowVertexGeometry` (open quads) + `ARArrowRenderer` (glow/core passes) | `arrow/ARArrowVertexGeometry.kt`, `arrow/ARArrowRenderer.kt` |
| Lane guidance presence | A lane-recommendation strip shown during navigation on highways | `LaneGuidanceStrip`, already wired into `LiveScreen` | `RoadAlertBanner.kt:110-160` |

## 2. Confirmed differences (concrete, in isolated-component scope)

### 2.1 Speed dial + speed-limit sign: orientation
- **Reference**: in all six frames, the circular speed dial and the speed-limit sign sit **side by side**
  (dial on the left, sign on the right), same baseline, directly above the bottom ETA bar.
- **Code**: `LiveSpeedBlock` is a single `Column` — the limit sign is stacked **above** the dial (`LiveScreen.kt:568-642`).
  The code's own comment ("posted-speed sign ABOVE the circular current-speed dial") describes this
  intentionally, but it does not match the provided reference frames.
- **Action**: new isolated component renders the same two children in a `Row`, bottom-aligned, dial first.
  Pure layout change — no new data, no behavior change.

### 2.2 Bottom ETA bar: missing drag handle
- **Reference**: every frame shows a short light-gray horizontal bar centered at the top of the bottom
  trip card (bottom-sheet drag-handle affordance).
- **Code**: `LiveTripSummary`'s own doc comment says "a drag handle, three equal-weight stats... and a
  single status line" (`LiveScreen.kt:1443-1449`), but the composable body never draws one — just the stats
  `Row` and the optional congestion `Row`.
- **Action**: new isolated variant adds a `36.dp × 4.dp` centered rounded bar (matching the handle already
  used in `MapControls.kt:530-531`) above the stats row.

### 2.3 Route Preview sheet: light theme vs. reference's dark theme
- **Reference**: the Route Preview panel (map + bottom sheet) is dark navy throughout — white "Route preview"
  title, light-gray subtitle, dark metric cards with white figures, a muted/dark map style behind the route line.
- **Code**: `BottomPanel` wraps **both** the home-discovery panel and the Route Preview content in one shared
  `.surfaceCard(GlassShapes.Card)` container (`MapControls.kt:503-507`), which is opaque **white**
  (`Glass.kt:34-38`, `OpenMapsColors.Surface = 0xFFFFFFFF`). `RouteMetricCard` fills with `SurfaceMuted` (light
  gray) and dark text (`MapControls.kt:983-1009`).
- **Scope note**: I have no reference frame for Search/Destination Details, so I cannot confirm those should
  stay light — `surfaceCard` is shared infrastructure used elsewhere. Recommend a **new, Route-Preview-specific
  dark container** rather than re-theming the shared `surfaceCard`, so Search/Home Discovery are untouched.
- **Action**: new isolated `RoutePreviewDark*` components (metric cards, alt-route rows, nav-mode selector,
  CTA row) as a self-contained set, not a change to `surfaceCard`/`Glass.kt`.

### 2.4 Route Preview: alternate-route rows need a real "Use" button
- **Reference**: each alternate route row has a distinct filled/pill **button** labeled "Use".
- **Code**: `RoutePreviewSummary`'s alternates loop renders "Use" as a plain colored `Text`, no button chrome
  (`MapControls.kt:956-960`).
- **Action**: isolated row variant with an actual pill-shaped button.

### 2.5 Route Preview: "Choose navigation view" selector cards
- **Reference**: two cards, each with an icon, title, one-line caption, and an explicit **radio circle**.
  The selected card ("Map Navigation") keeps a dark/neutral fill and gets a **blue outline** + filled radio;
  the unselected card ("Live Camera") gets a thin neutral border + empty radio. Neither card is solid-filled blue.
- **Code**: `NavigationModeCard` fills the **selected** card solid `ElectricBlueFilled` (white text) and the
  unselected card `SurfaceMuted` (gray) — no icon, no radio indicator, no outline-only selected state
  (`NavigationControls.kt:456-488`).
- **Action**: new isolated card variant: icon + title + caption + radio circle, selected = outline + filled
  radio (not full-bleed fill), unselected = thin border + empty radio.

### 2.6 Route Preview: Start / Steps CTA row
- **Reference**: "Start Map Navigation" (wide, filled blue pill, icon + label) and "Steps" (visible pill/button
  with icon + label) sit **side by side in one row**, Start taking most of the width.
- **Code**: `NavigationControls` renders Start as a full-width pill button, then **"Steps" as a separate,
  borderless `TextButton` stacked below it**, right-aligned (`NavigationControls.kt:363-412`).
- **Action**: new isolated CTA row: Start (`weight ~0.7`) + Steps as a bordered pill button (`weight ~0.3`),
  same row.

### 2.7 Lane guidance card: richness
- **Reference**: a two-line header ("Keep right" bold + "Use either of the 2 right lanes" explanatory line)
  plus lane-divider-style glyphs (vertical bars with arrows), two of four highlighted solid blue.
- **Code**: `LaneGuidanceStrip` shows a single small "Use lane" caption plus a row of single-character arrow
  glyphs (`←↑→` etc.), recommended ones tinted blue with a light pill background (`RoadAlertBanner.kt:110-160`).
  Functionally equivalent (recommended vs. muted lanes), visually plainer than the reference.
- **Action**: new isolated `LaneGuidanceCard` consuming the same `LaneGuidance`/`LaneDirection` contract,
  styled closer to the reference (two-line header, bar-style lane glyphs). Existing `LaneGuidanceStrip` is
  left untouched since it's live-wired.

## 3. Differences needing a product decision (not purely visual — flagging, not changing)

- **Speed dial arc semantics**: code computes `arcFraction` against a **fixed design scale** (120 mph /
  200 km/h — `LiveScreen.kt:646-651`), so at ordinary speeds (24–67 mph) the ring should read as mostly empty.
  The reference frames visually read as a **near-full ring at every shown speed** (24/25, 28/30, 67/65, 54/55),
  which is more consistent with a speed-vs-**posted-limit** ratio. Changing what the ring measures is a
  product/safety decision (it changes the information conveyed, not just styling), so this is flagged for
  sign-off rather than changed here. Needs a zoomed on-device or cropped-reference pixel check before anyone
  touches `speedArcFraction`.
- **AR destination marker shape**: code deliberately reuses the POI marker language (chip + stem + dot) for
  the destination, "so it reads as one consistent marker language rather than a bespoke one-off shape"
  (`LiveScreen.kt:982-986`). The reference shows a more distinct teardrop/pin shape fused to the "Destination"
  label. This is an intentional consistency choice already explained in the code, not an obvious bug — flagging
  for a design call, not silently overriding it.
- **Live/Map toggle separator**: the 3-panel composite shows a small green dot between "Live" and "Map"; the
  other reference frames show a plain "|" divider instead. These two reference sources disagree with each
  other, and current code (`MapModeToggle`) draws neither (continuous pill, no separator glyph at all) — this
  is left unresolved pending clarification rather than guessing which (if either) is canonical.

## 4. Out of scope for this phase (explicitly not touched)

- **AR chevron route-tangent orientation**: reference frames show each chevron subtly rotated to the route's
  local curve direction; `ARArrowVertexGeometry.write` currently always draws chevrons pointing straight
  up-screen (no heading parameter) — positions follow the curved route, but individual chevron orientation
  does not. This matches the state-contract/sampling work already tracked in issue #46 (Muse's
  `ARArrowPipeline`/anchor lifecycle territory) and the explicit instruction not to touch the OpenGL pipeline
  or insert screen-fixed arrows in this phase. Documented here for Phase 2 only.
- **Chevron glow/thickness exact tuning**: current two-pass glow (`GLOW_RADIUS_SCALE = 2.1f`,
  `GLOW_ALPHA_SCALE = 0.38f`, core color `(66,165,255)`, glow color `(140,225,255)`) looks directionally
  consistent with the reference's neon-bloom look, but confirming it is pixel/brightness-accurate requires an
  on-device or emulator screenshot, which isn't available here. No renderer change made.
- **Native map styling** (the muted/dark map tiles, route-line styling, highway shields, puck, and red
  destination pin visible in the Route Preview reference): this is MapLibre style JSON / native layer
  configuration, not a Compose component, and outside this phase's Compose-only isolated-component scope.

## 5. Component inventory for Phase 1 build (new files only)

All new, none wired into production screens yet:
- `LiveSpeedRow` — side-by-side dial + limit sign (§2.1)
- `LiveTripBarHandle` addition — drag-handle bar (§2.2)
- `RoutePreviewDarkMetrics` — dark metric-card row + traffic-delay line (§2.3)
- `RoutePreviewDarkAlternateRow` — dark alt-route row with real "Use" button (§2.3, §2.4)
- `NavigationViewOptionCard` — icon + radio selector card (§2.5)
- `RoutePreviewCtaRow` — Start + Steps side by side (§2.6)
- `LaneGuidanceCard` — richer two-line lane card (§2.7)

Each ships with a `@Preview` using realistic, clearly-labeled debug-only fixtures (no production data wired).
