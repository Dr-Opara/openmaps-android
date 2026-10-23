# OpenMaps destination photos — gallery v1

## Product behavior: UNIVERSAL, not airport-specific

Every supported destination-details flow — typed street address, map pin, restaurant,
hotel, workplace, airport, landmark, business or other POI — must automatically request
a photo preview based on the selected destination's identity and coordinates.
William P. Hobby Airport is only one test case, not a hardcoded feature.

**Source precedence for the default top thumbnail:**

1. **Exact place**: Verified/licensed destination photo (currently Wikimedia Commons).
2. **Nearby streets**: When no verified venue image is found, optionally show nearby
   Mapillary street imagery at the selected coordinate, prominently labelled
   **"Nearby street view"**. It may not depict the building or entry itself.
3. **No trustworthy image**: Keep the attractive neutral placeholder. Never use
   unrelated stock imagery or say an image shows a specific home/business without proof.

The Photos tab groups exact photos and street views separately, each with source/credit.
A user should never need to open the Photos tab before the photo preview appears.
Mapillary is *optional*, configured with a public token, and is not necessary to
retrieve Wikimedia images. Coverage is best effort: free providers cannot guarantee
a photograph exists for every address or business.

**Device acceptance matrix (check each on an actual Android phone):**
- Named airport: real exact-place hero or clearly labelled nearby street view.
- Restaurant and hotel: correctly identified exact-place image, distinct location, no
  false chain-brand match.
- Popular landmark or park: recognizable, attributed Commons picture when available.
- Full street address: context street view if available; never mislabel a nearby house
  or unrelated road image as the exact property.
- Unnamed point/pin: no fake photo if no source or trustworthy identity.
- No Mapillary token: exact Wikimedia lookup still runs and the page still works.
- Offline/no network: placeholders remain responsive and navigation actions still work.
- Selecting multiple destinations in succession: never show the prior destination's
  cover while loading the next.

Future expansion: provider-neutral Wikidata/OSM identity, attributed Wikidata P18,
licensed business-photo feeds, and opt-in user submissions with moderation. The
current exact-name matching is limited for small businesses and street addresses.

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
