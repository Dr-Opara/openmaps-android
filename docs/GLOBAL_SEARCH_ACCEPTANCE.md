# Global Search Acceptance Criteria

This suite is the release gate for OpenMaps **Global Search / Geocoding**.

Run from the repository root on Windows:

```powershell
.\scripts\global-search-acceptance.ps1
```

The script exits with code `0` only when every case passes.

## Required cases

1. Exact U.S. street address — 2880 Jordan Ranch Blvd, Brookshire, TX 77423.
2. Named school/POI — Melton Elementary School.
3. International landmark — Eiffel Tower, Paris.
4. International exact address — 10 Downing Street, London.
5. International POI outside Europe/North America — Lekki Conservation Centre, Lagos.
6. Reverse geocoding — coordinates must resolve to a non-empty human-readable location.

## Pass conditions

For every forward-search case:

- HERE must be present in the responding provider set.
- At least one result must be returned.
- At least one expected location token must appear in the result payload.
- End-to-end gateway latency must be <= 4 seconds for this acceptance run.

Reverse geocoding must return a non-null result with a non-empty `displayName`.

These checks are broad enough to survive provider wording changes while still catching user-visible failures: zero results, wrong geography, provider activation loss, unusable latency, or broken reverse geocoding.
