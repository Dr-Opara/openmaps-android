# Production Routing + Traffic Acceptance

This is the release gate for OpenMaps production routing and traffic provider connectivity.

Run from the repository root on Windows:

```powershell
.\scripts\production-routing-acceptance.ps1
```

The suite checks representative routes in Houston, London, Paris, Lagos, Tokyo, and Sydney.

Each route must:

- be served by the HERE-backed OpenMaps routing gateway;
- be traffic-aware;
- contain non-empty route geometry;
- contain turn-by-turn maneuvers;
- report positive distance and duration;
- complete within the acceptance latency threshold of 6 seconds.

The suite also sends one route geometry to the live HERE Traffic incident gateway and requires a
successful HERE response. A route with zero current incidents is a valid success.

Route alternatives and exit/signpost guidance are recorded but are not mandatory on every route:
both are road/provider-data dependent. Their absence on a particular acceptance route is not a
reason to invent them.

## Separate local verification

The JVM unit suite covers:

- routing backend parsing;
- traffic-aware timing metadata;
- provider failover;
- selectable alternatives;
- exit/signpost formatting;
- heading-aware on-device snap-to-road matching;
- map-match confidence;
- HERE traffic incident parsing.

The physical-device validation must still verify that noisy GPS, rerouting, Map/Live continuity,
and AR arrows behave correctly while actually driving.
