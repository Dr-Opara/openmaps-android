# OpenMaps destination photos — gallery v1

## What is built

- The top image area on Destination Details and Place Details shows the **first verified destination image** as a clickable hero thumbnail. It opens the Photos tab; it never displays a nearby street scene as the venue's cover.
- The Photos tab has small destination-photo tiles and a full-screen reader with next/previous and source/credit link.
- Nearby street-level photos appear in their **own** clearly labelled section and are optional.
- No photos: retain the neutral category placeholder, show a clear empty state in Photos.
- The data source is intentionally bounded at four Commons photos and four Mapillary views. All photo downloads happen off the main thread.

## Providers

### Wikimedia Commons

No API key. Search for the exact named destination and conservatively match file titles; airports can use an exact-name Commons category when search is insufficient. This is useful for William P. Hobby Airport (Wikimedia Commons has a category named `William P. Hobby Airport`).

Every image is checked for a known permissive license and displays its author/license, plus a link to the file page. Content whose identity is ambiguous or whose license is not on the allowlist is skipped. **Do not infer that an arbitrary nearby photo is of the selected place.**

Wikimedia usage rules: compliant User-Agent, rate limits, caching, license and author requirements. Review commercial release obligations and add server-side caching/backoff before large-scale launch.

### Mapillary nearby street views

Optional. OpenMaps can fetch a few street-level views inside a small coordinate bounding box and verifies each returned image's coordinate is close to the destination. They are displayed as **Nearby street views**, not `Destination photos`.

In local, git-ignored `local.properties` on the developer machine:

```properties
MAPILLARY_CLIENT_TOKEN=YOUR_PUBLIC_MAPILLARY_CLIENT_TOKEN
```

Alternatively pass `MAPILLARY_CLIENT_TOKEN` as a Gradle property or environment variable. Do not commit credentials. The key is a **public client token**, so it will be visible inside a shipped APK; never supply a secret server-side token here. Check Mapillary's current API terms before production.

Mapillary imagery is attributed to its photographer and source page. Source licensing (generally CC BY-SA) and provider terms must be honored.

## Limits / acceptance

- No API can supply accurate photos for every small business, address or home.
- No synthetic placeholder presented as a real photo.
- Unit tests cover conservative title/license matching, not upstream API reliability.
- Android CI and a real-phone screenshot test are required.
- Photo review: named airport, major landmark, chain restaurant in two different cities, street address, and destination with no image.
- Add pagination, cache TTL, 429/503 Retry-After backoff, tighter file-license URL metadata, exact provider IDs/Wikidata links and accessibility/localization before production scale.
