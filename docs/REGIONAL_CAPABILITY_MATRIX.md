# Regional Capability Matrix / Beta Readiness

Status meanings:

- **VERIFIED** — directly exercised by a production acceptance case in that region.
- **SUPPORTED** — production path/provider is configured, but this exact region lacks direct evidence.
- **CONDITIONAL** — shown only when provider/road evidence is trustworthy; otherwise hidden/degraded.
- **FALLBACK** — safe fallback exists, but the preferred regional experience is incomplete.
- **UNVERIFIED** — implementation exists but regional/physical validation is still required.
- **BLOCKED** — known release dependency prevents certification.
- **UNSUPPORTED** — OpenMaps does not currently provide the capability.

The machine-readable source of truth is
[`config/regional-capabilities.json`](../config/regional-capabilities.json).

This matrix must be reviewed again after each production provider or localization change.

## Launch acceptance cities

| Capability | Houston | London | Paris | Lagos | Tokyo | Sydney |
|---|---|---|---|---|---|---|
| Search | VERIFIED | VERIFIED | VERIFIED | VERIFIED | SUPPORTED | SUPPORTED |
| Reverse geocoding | SUPPORTED | VERIFIED | SUPPORTED | SUPPORTED | SUPPORTED | SUPPORTED |
| Routing | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED |
| Traffic-aware ETA | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED |
| Traffic incidents | VERIFIED | SUPPORTED | SUPPORTED | SUPPORTED | SUPPORTED | SUPPORTED |
| LaneLock | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL |
| Speed limits | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL | CONDITIONAL |
| Places | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED |
| Parking POIs | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED | VERIFIED |
| Live parking occupancy | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED |
| Community reports | SUPPORTED | SUPPORTED | SUPPORTED | SUPPORTED | SUPPORTED | SUPPORTED |
| Navigation voice | VERIFIED | VERIFIED | VERIFIED | VERIFIED | FALLBACK | FALLBACK |
| App localization | VERIFIED | VERIFIED | VERIFIED | FALLBACK | FALLBACK | FALLBACK |
| Live AR / VPS | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED |
| Production map platform | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED |
| Cached offline route | SUPPORTED* | SUPPORTED* | SUPPORTED* | SUPPORTED* | SUPPORTED* | SUPPORTED* |
| Offline map corridor | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED |

`* Phase 12 implementation is awaiting merge/CI at the time this matrix was introduced.`

## Evidence rules

A capability must not be promoted to VERIFIED from documentation or provider marketing alone.

- Routing + traffic-aware ETA: production acceptance directly exercises Houston, London, Paris,
  Lagos, Tokyo and Sydney and requires HERE + `trafficAware=true`.
- Places: production acceptance directly exercises all six cities and requires the HERE commercial
  provider to participate with real results across multiple categories.
- Search: current acceptance directly covers Texas/US, London/UK, Paris/France and Lagos/Nigeria.
  Tokyo and Sydney remain SUPPORTED until explicit search cases are added.
- Traffic incidents: the existing acceptance directly exercises a Houston route corridor. Other
  target regions are not promoted beyond SUPPORTED merely because the provider endpoint exists.
- LaneLock: never becomes globally VERIFIED. It is road-segment/evidence dependent by design.
- AR/VPS: remains BLOCKED until production ARCore provisioning and physical-device regional
  validation occur.
- Map/offline-map: remain BLOCKED while the default OpenFreeMap surface is development-only and no
  production tile/offline contract has been accepted.
- Voice/localization: Tokyo is not marked verified because Japanese app/navigation voice support is
  not implemented. Sydney uses English fallback without a dedicated en-AU voice/terminology profile.

## Current public-beta blockers

1. Select and activate the production map/tile provider.
2. Implement/certify offline map-corridor behavior against that provider's license/cache contract.
3. Provision ARCore Geospatial production credentials and validate actual VPS/Geospatial behavior.
4. Complete the physical Android field matrix in `ANDROID_FIELD_VALIDATION.md`.
5. Resolve any red CI/acceptance gate introduced by Phases 10–13.
6. Produce and sign the release AAB/APK with production signing credentials.

The matrix is intentionally conservative: an unavailable regional enhancement must degrade to normal
Map navigation rather than being implied or fabricated.
