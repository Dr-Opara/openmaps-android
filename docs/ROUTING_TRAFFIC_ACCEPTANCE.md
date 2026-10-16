# Production Routing + Traffic Acceptance

This is the automated release gate for OpenMaps workstream **2. Production Routing + Traffic**.

Run from the repository root on Windows:

```powershell
.\scripts\production-routing-acceptance.ps1
```

The gate validates the deployed OpenMaps backend, not mocks.

## Automated coverage

- Traffic-aware HERE routing in Houston, London, Paris, Lagos, Tokyo and Sydney.
- Non-empty provider route geometry, duration, distance and maneuver guidance.
- Provider-backed route alternatives are requested and reported when HERE supplies them.
- HERE live traffic incident endpoint accepts a real returned route corridor.
- LaneLock production capability is checked against the deployed lane gateway.
- A lane response of `available` passes.
- `no_trustworthy_lane_guidance` also passes because OpenMaps must hide LaneLock rather than guess.
- `provider_access_required`, provider configuration errors, HTTP failures or malformed responses fail the release gate.

## Manual Android release gate

The automated suite is necessary but not sufficient. Before marking routing complete:

1. `.\gradlew.bat clean assembleDebug`
2. `.\gradlew.bat testDebugUnitTest`
3. `.\gradlew.bat lint`
4. Install the debug APK on a physical Android device.
5. Drive a real route and verify:
   - the vehicle position stays road-snapped through normal GPS noise;
   - a real wrong turn transitions off-route and reroutes once;
   - the replacement route remains traffic-aware;
   - Map <-> Live preserves the same trip;
   - real incidents/closures appear only when the provider returns them;
   - exit/signpost guidance matches the road;
   - LaneLock appears only when provider-backed lane data exists;
   - no fake traffic, lane or incident state appears during unavailable/degraded conditions.

A workstream is complete only when both the automated and physical-device gates pass.
