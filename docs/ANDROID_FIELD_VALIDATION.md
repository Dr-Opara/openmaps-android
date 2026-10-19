# Android Physical Field Validation

This checklist is a **release gate**, not a documentation exercise. Automated tests cannot mark any
row PASS. A row moves from NOT RUN only after a physical ARCore-capable Android device executes it.

| Area | Required validation | Status |
|---|---|---|
| Clean install | Install release candidate, first launch, permissions | NOT RUN |
| Screen lock | Lock for >=5 min during navigation; guidance/location/trip share continue | NOT RUN |
| Background | Background for >=10 min; location/reroute/share remain alive | NOT RUN |
| Foreground return | Return from background without duplicate session/restart | NOT RUN |
| Process recreation | Kill process under memory pressure, reopen, restore route safely | NOT RUN |
| Long trip | 60+ min continuous navigation | NOT RUN |
| Trip sharing | 30+ min share, background + lock + arrival cleanup | NOT RUN |
| Intermittent cellular | Repeated airplane/no-service transitions and recovery | NOT RUN |
| Provider outage | Route/search/places/voice degradation remains explicit | NOT RUN |
| GPS drift | Urban canyon/noisy fixes do not trigger reroute loops | NOT RUN |
| Tunnel | Lose/regain GPS through tunnel-like interval | NOT RUN |
| Wrong turn | Off-route confirmation then successful reroute | NOT RUN |
| Closures/incidents | Live severe event appears and trusted reroute behaves correctly | NOT RUN |
| LaneLock | Real multi-lane approach; only trustworthy lane evidence shown | NOT RUN |
| Highway exits | Exit/signpost guidance matches physical road signs | NOT RUN |
| Map ↔ Live | Switch Map↔Live at least 20 times during one trip | NOT RUN |
| ARCore Earth | `verifyProductionArCoreConfiguration` passes; installed build no longer reports `ERROR_NOT_AUTHORIZED`; Earth reaches usable tracking outdoors | NOT RUN |
| AR arrows | Road alignment, stability, heading and distance behavior | NOT RUN |
| Overpass/elevation | Validate arrow lift/alignment on grades/overpasses | NOT RUN |
| Bluetooth | Spoken guidance through vehicle/headset | NOT RUN |
| Audio interruption | Phone call/music ducking/focus recovery | NOT RUN |
| Battery | Record battery delta on 60-minute drive | NOT RUN |
| Thermal | No thermal shutdown/severe throttling on 60-minute drive | NOT RUN |
| Memory | No unbounded growth across long trip / repeated Map↔Live | NOT RUN |
| Arrival | Voice, foreground service, sharing and route state cleanly terminate | NOT RUN |
| Relaunch | No stale notification/share/recovery state after completed trip | NOT RUN |

## Pass criteria

- No crash, ANR, stuck foreground notification or unrecoverable blank camera/map.
- No navigation location collection after an ended trip.
- No stale public trip-share location after stop/arrival.
- Cached/degraded route is visibly identified and replaced after connectivity returns.
- LaneLock is hidden when evidence conflicts or is missing.
- AR limitations are shown honestly; CameraX/Map fallback never blocks core navigation.
- ARCore Earth cannot pass while authorization is missing, even if the camera preview itself works.
- Any defect found here reopens the owning production phase before public beta.
