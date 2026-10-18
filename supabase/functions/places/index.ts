import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type PlaceCategory =
  | "Restaurant" | "Coffee" | "Hotel" | "Grocery" | "Pharmacy" | "Parking"
  | "Gas" | "EvCharging" | "Bank" | "Atm" | "Convenience" | "Medical"
  | "Entertainment" | "Shopping" | "Education";

type PlacesRequest = {
  center?: Coordinate;
  radiusMeters?: number;
  categories?: PlaceCategory[];
  localeTag?: string | null;
};

const HERE_CATEGORY_IDS: Record<PlaceCategory, string[]> = {
  Restaurant: ["100-1000"],
  Coffee: ["100-1100"],
  Hotel: ["500-5000"],
  Grocery: ["600-6300-0066"],
  Pharmacy: ["600-6400"],
  Parking: ["800-8500"],
  Gas: ["700-7600-0000", "700-7600-0116"],
  EvCharging: ["700-7600-0322", "700-7600-0323", "700-7600-0324"],
  Bank: ["700-7000"],
  Atm: ["700-7010"],
  Convenience: ["600-6000"],
  Medical: ["800-8000"],
  Entertainment: ["200", "300"],
  Shopping: ["600"],
  Education: ["800-8200", "800-8250"],
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "private, max-age=120",
    },
  });
}

function authorized(req: Request): boolean {
  const supplied = req.headers.get("apikey");
  if (!supplied) return false;
  try {
    const keys = JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}");
    return Object.values(keys).includes(supplied);
  } catch {
    return false;
  }
}


function serviceRoleKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim() || null;
}

function supabaseUrl(): string | null {
  return Deno.env.get("SUPABASE_URL")?.trim() || null;
}

async function adminFetch(path: string, init: RequestInit = {}): Promise<Response | null> {
  const base = supabaseUrl();
  const key = serviceRoleKey();
  if (!base || !key) return null;
  const headers = new Headers(init.headers);
  headers.set("apikey", key);
  headers.set("authorization", "Bearer " + key);
  if (!headers.has("content-type") && init.body) headers.set("content-type", "application/json");
  return fetch(base + path, { ...init, headers });
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function clientAddress(req: Request): string {
  return req.headers.get("cf-connecting-ip")?.trim()
    || req.headers.get("x-forwarded-for")?.split(",")[0]?.trim()
    || "unknown";
}

async function consumePlacesRateLimit(req: Request): Promise<boolean> {
  const base = supabaseUrl();
  const key = serviceRoleKey();
  if (!base || !key) return true;
  try {
    const clientHash = await sha256Hex(clientAddress(req));
    const now = new Date();
    now.setSeconds(0, 0);
    const response = await adminFetch("/rest/v1/rpc/consume_navigation_rate_limit", {
      method: "POST",
      body: JSON.stringify({
        p_client_hash: clientHash,
        p_endpoint: "places",
        p_bucket_start: now.toISOString(),
        p_limit: 60,
      }),
    });
    if (!response || !response.ok) return true;
    return (await response.json()) === true;
  } catch {
    return true;
  }
}

async function writePlacesTelemetry(event: {
  categories: PlaceCategory[];
  radiusMeters: number;
  resultCount: number;
  providers: string[];
  latencyMs: number;
  outcome: "success" | "zero_results" | "error";
  fallbackUsed: boolean;
  usedCache: boolean;
}): Promise<void> {
  try {
    await adminFetch("/rest/v1/places_telemetry", {
      method: "POST",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({
        categories: event.categories,
        radius_meters: Math.round(event.radiusMeters),
        result_count: event.resultCount,
        providers: event.providers,
        latency_ms: event.latencyMs,
        outcome: event.outcome,
        fallback_used: event.fallbackUsed,
        used_cache: event.usedCache,
      }),
    });
  } catch {
    // Places must never fail because observability failed.
  }
}


async function placesCacheKey(
  center: Coordinate,
  radiusMeters: number,
  categories: PlaceCategory[],
  localeTag: string | null,
): Promise<string> {
  const roundedLat = center.latitude.toFixed(3);
  const roundedLon = center.longitude.toFixed(3);
  const radiusBucket = Math.round(radiusMeters / 250) * 250;
  return sha256Hex([
    "places-v1",
    roundedLat,
    roundedLon,
    String(radiusBucket),
    [...categories].sort().join(","),
    localeTag ?? "",
  ].join("|"));
}

async function readPlacesCache(key: string): Promise<{ places: any[]; providers: string[] } | null> {
  try {
    const now = encodeURIComponent(new Date().toISOString());
    const response = await adminFetch(
      "/rest/v1/places_cache?cache_key=eq." + encodeURIComponent(key) +
      "&expires_at=gt." + now +
      "&select=response,providers&limit=1",
      { method: "GET" },
    );
    if (!response || !response.ok) return null;
    const rows = await response.json();
    if (!Array.isArray(rows) || rows.length === 0 || !Array.isArray(rows[0]?.response)) return null;
    return {
      places: rows[0].response,
      providers: Array.isArray(rows[0].providers) ? rows[0].providers : [],
    };
  } catch {
    return null;
  }
}

async function writePlacesCache(key: string, places: any[], providers: string[]): Promise<void> {
  if (places.length === 0) return;
  try {
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    await adminFetch("/rest/v1/places_cache?on_conflict=cache_key", {
      method: "POST",
      headers: { prefer: "resolution=merge-duplicates,return=minimal" },
      body: JSON.stringify({
        cache_key: key,
        response: places,
        providers,
        expires_at: expiresAt,
      }),
    });
  } catch {
    // Cache is best-effort.
  }
}

function validCoordinate(value: Coordinate | undefined): value is Coordinate {
  return value != null &&
    Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function firstString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function categoryFor(ids: string[], requested: PlaceCategory[]): PlaceCategory | null {
  for (const category of requested) {
    const prefixes = HERE_CATEGORY_IDS[category];
    if (ids.some((id) => prefixes.some((prefix) => id === prefix || id.startsWith(prefix + "-")))) {
      return category;
    }
  }
  return null;
}

function normalize(item: Record<string, unknown>, requested: PlaceCategory[]) {
  const position = item.position && typeof item.position === "object"
    ? item.position as Record<string, unknown>
    : null;
  const latitude = Number(position?.lat);
  const longitude = Number(position?.lng);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;

  const categories = Array.isArray(item.categories)
    ? item.categories as Array<Record<string, unknown>>
    : [];
  const ids = categories.map((c) => firstString(c.id)).filter((v): v is string => v != null);
  const category = categoryFor(ids, requested);
  if (!category) return null;

  const address = item.address && typeof item.address === "object"
    ? item.address as Record<string, unknown>
    : null;
  const chains = Array.isArray(item.chains)
    ? item.chains as Array<Record<string, unknown>>
    : [];
  const brand = chains.map((c) => firstString(c.name)).find((v) => v != null) ?? null;

  const contacts = Array.isArray(item.contacts)
    ? item.contacts as Array<Record<string, unknown>>
    : [];
  const firstContact = contacts[0];
  const phones = Array.isArray(firstContact?.phone)
    ? firstContact.phone as Array<Record<string, unknown>>
    : [];
  const websites = Array.isArray(firstContact?.www)
    ? firstContact.www as Array<Record<string, unknown>>
    : [];
  const phone = phones.map((entry) => firstString(entry.value)).find((v) => v != null) ?? null;
  const website = websites.map((entry) => firstString(entry.value)).find((v) => v != null) ?? null;

  const openingHours = Array.isArray(item.openingHours)
    ? item.openingHours as Array<Record<string, unknown>>
    : [];
  const firstHours = openingHours[0];
  const openNow = typeof firstHours?.isOpen === "boolean" ? firstHours.isOpen : null;
  const hoursText = Array.isArray(firstHours?.text)
    ? (firstHours.text as unknown[]).map(firstString).filter((v): v is string => v != null).join("; ")
    : null;

  const access = Array.isArray(item.access)
    ? item.access as Array<Record<string, unknown>>
    : [];
  const firstAccess = access[0];
  const accessLatitude = Number(firstAccess?.lat);
  const accessLongitude = Number(firstAccess?.lng);

  return {
    id: "here:" + String(item.id ?? ""),
    name: firstString(item.title) ?? category,
    category,
    latitude,
    longitude,
    address: firstString(address?.label),
    distanceMeters: typeof item.distance === "number" ? item.distance : null,
    openNow,
    metadata: {
      parkingType: null,
      isPublicAccess: null,
      feeApplies: null,
      brand,
      connectors: [],
      phone,
      website,
      openingHoursText: hoursText || null,
      accessLatitude: Number.isFinite(accessLatitude) ? accessLatitude : null,
      accessLongitude: Number.isFinite(accessLongitude) ? accessLongitude : null,
    },
  };
}

async function browseHere(
  center: Coordinate,
  radiusMeters: number,
  categories: PlaceCategory[],
  apiKey: string,
  localeTag: string | null,
) {
  const ids = Array.from(new Set(categories.flatMap((c) => HERE_CATEGORY_IDS[c] ?? [])));
  if (ids.length === 0) return [];

  const params = new URLSearchParams({
    at: center.latitude + "," + center.longitude,
    in: "circle:" + center.latitude + "," + center.longitude + ";r=" + Math.round(radiusMeters),
    categories: ids.join(","),
    limit: "60",
    apiKey,
  });

  if (localeTag) params.set("lang", localeTag);

  const response = await fetch(
    "https://browse.search.hereapi.com/v1/browse?" + params.toString(),
    { headers: { accept: "application/json" }, signal: AbortSignal.timeout(7000) },
  );
  if (response.status === 429) throw new Error("HERE_RATE_LIMITED");
  if (!response.ok) throw new Error("HERE_" + response.status);

  const body = await response.json() as Record<string, unknown>;
  const items = Array.isArray(body.items) ? body.items as Array<Record<string, unknown>> : [];
  return items.map((item) => normalize(item, categories)).filter((item) => item != null);
}



const OSM_SELECTORS: Partial<Record<PlaceCategory, Array<[string, string]>>> = {
  Restaurant: [["amenity", "restaurant"], ["amenity", "fast_food"]],
  Coffee: [["amenity", "cafe"]],
  Hotel: [["tourism", "hotel"], ["tourism", "motel"], ["tourism", "guest_house"]],
  Grocery: [["shop", "supermarket"], ["shop", "grocery"]],
  Pharmacy: [["amenity", "pharmacy"]],
  Parking: [["amenity", "parking"]],
  Gas: [["amenity", "fuel"]],
  EvCharging: [["amenity", "charging_station"]],
  Bank: [["amenity", "bank"]],
  Atm: [["amenity", "atm"]],
  Convenience: [["shop", "convenience"]],
  Medical: [["amenity", "clinic"], ["amenity", "hospital"], ["amenity", "doctors"]],
  Entertainment: [["amenity", "cinema"], ["amenity", "theatre"], ["tourism", "attraction"]],
  Shopping: [["shop", "mall"], ["shop", "department_store"], ["shop", "clothes"], ["shop", "shoes"], ["shop", "electronics"]],
  Education: [["amenity", "school"], ["amenity", "college"], ["amenity", "university"], ["amenity", "kindergarten"]],
};

function osmCategory(tags: Record<string, string>, requested: PlaceCategory[]): PlaceCategory | null {
  for (const category of requested) {
    const selectors = OSM_SELECTORS[category] ?? [];
    if (selectors.some(([key, value]) => tags[key] === value)) return category;
  }
  return null;
}

function osmAddress(tags: Record<string, string>): string | null {
  const house = firstString(tags["addr:housenumber"]);
  const street = firstString(tags["addr:street"]);
  if (house && street) return house + " " + street;
  return street;
}

function osmMetadata(category: PlaceCategory, tags: Record<string, string>) {
  return {
    parkingType: category === "Parking" ? firstString(tags.parking) : null,
    isPublicAccess: category === "Parking"
      ? (["yes", "public", "permissive"].includes(tags.access) ? true : ["private", "no"].includes(tags.access) ? false : null)
      : null,
    feeApplies: category === "Parking"
      ? (tags.fee === "yes" ? true : tags.fee === "no" ? false : null)
      : null,
    brand: category === "Gas"
      ? firstString(tags.brand) ?? firstString(tags.operator)
      : category === "EvCharging"
      ? firstString(tags.operator) ?? firstString(tags.network)
      : firstString(tags.brand),
    connectors: category === "EvCharging"
      ? Object.keys(tags).filter((key) => key.startsWith("socket:")).map((key) => key.slice("socket:".length)).sort()
      : [],
    phone: firstString(tags.phone) ?? firstString(tags["contact:phone"]),
    website: firstString(tags.website) ?? firstString(tags["contact:website"]),
    openingHoursText: firstString(tags.opening_hours),
    accessLatitude: null,
    accessLongitude: null,
  };
}

async function browseOsm(
  center: Coordinate,
  radiusMeters: number,
  categories: PlaceCategory[],
  localeTag: string | null,
) {
  const selectors = categories.flatMap((category) => OSM_SELECTORS[category] ?? []);
  if (selectors.length === 0) return [];

  const clauses = selectors
    .map(([key, value]) => `nwr["${key}"="${value}"](around:${Math.round(radiusMeters)},${center.latitude},${center.longitude});`)
    .join("\n  ");
  const query = `[out:json][timeout:8];(\n  ${clauses}\n);out body center 80;`;

  const endpoints = [
    "https://overpass.openstreetmap.fr/api/interpreter",
    "https://overpass-api.de/api/interpreter",
  ];

  for (const endpoint of endpoints) {
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/x-www-form-urlencoded; charset=utf-8",
          accept: "application/json",
          "user-agent": "OpenMaps/1.0 global-places gateway",
        },
        body: "data=" + encodeURIComponent(query),
        signal: AbortSignal.timeout(7000),
      });
      if (!response.ok) continue;
      const body = await response.json() as { elements?: Array<Record<string, unknown>> };
      const elements = Array.isArray(body.elements) ? body.elements : [];
      return elements.map((element) => {
        const tags = element.tags && typeof element.tags === "object"
          ? element.tags as Record<string, string>
          : {};
        const category = osmCategory(tags, categories);
        if (!category) return null;
        const centerObj = element.center && typeof element.center === "object"
          ? element.center as Record<string, unknown>
          : null;
        const latitude = Number(element.lat ?? centerObj?.lat);
        const longitude = Number(element.lon ?? centerObj?.lon);
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
        return {
          id: "osm:" + String(element.type ?? "node") + "/" + String(element.id ?? ""),
          name: localizedOsmName(tags, localeTag) ?? category,
          category,
          latitude,
          longitude,
          address: osmAddress(tags),
          distanceMeters: null,
          openNow: null,
          metadata: osmMetadata(category, tags),
        };
      }).filter((item) => item != null);
    } catch {
      // Try next worldwide Overpass mirror.
    }
  }
  throw new Error("OSM_UNAVAILABLE");
}

function localizedOsmName(tags: Record<string, string>, localeTag: string | null): string | null {
  const language = localeTag?.split("-")[0]?.toLowerCase();
  if (language) {
    const localized = firstString(tags["name:" + language]);
    if (localized) return localized;
  }
  return firstString(tags.name);
}

function normalizeLocaleTag(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > 35 || !/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(trimmed)) return null;
  return trimmed;
}

function normalizeText(value: string | null | undefined): string {
  return (value ?? "").toLowerCase().normalize("NFKD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[^\p{L}\p{N}]+/gu, " ").trim().replace(/\s+/g, " ");
}

function metersBetween(a: { latitude: number; longitude: number }, b: { latitude: number; longitude: number }): number {
  const rad = Math.PI / 180;
  const dLat = (b.latitude - a.latitude) * rad;
  const dLon = (b.longitude - a.longitude) * rad;
  const lat1 = a.latitude * rad;
  const lat2 = b.latitude * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function samePlace(a: any, b: any): boolean {
  if (a.category !== b.category) return false;
  if (metersBetween(a, b) > 80) return false;
  const an = normalizeText(a.name);
  const bn = normalizeText(b.name);
  if (an && bn && an === bn) return true;
  const aa = normalizeText(a.address);
  const ba = normalizeText(b.address);
  return aa.length > 0 && aa === ba;
}

function mergeDeduplicate(primary: any[], fallback: any[]): any[] {
  const kept = [...primary];
  for (const candidate of fallback) {
    if (!kept.some((existing) => samePlace(existing, candidate))) kept.push(candidate);
    if (kept.length >= 60) break;
  }
  return kept.slice(0, 60);
}

Deno.serve(async (req: Request) => {
  const startedAt = performance.now();
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);
  if (!(await consumePlacesRateLimit(req))) return json({ error: "rate_limited" }, 429);

  let body: PlacesRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!validCoordinate(body.center)) return json({ error: "invalid_center" }, 400);

  const categories = Array.isArray(body.categories)
    ? body.categories.filter((category): category is PlaceCategory => category in HERE_CATEGORY_IDS)
    : [];
  if (categories.length === 0) {
    return json({ provider: "here", capability: "available", places: [] });
  }

  const radiusMeters = Math.min(8000, Math.max(250, Number(body.radiusMeters) || 1500));
  const apiKey = Deno.env.get("HERE_API_KEY")?.trim() || null;
  const localeTag = normalizeLocaleTag(body.localeTag);

  const cacheKey = await placesCacheKey(body.center, radiusMeters, categories, localeTag);
  const cached = await readPlacesCache(cacheKey);
  if (cached) {
    void writePlacesTelemetry({
      categories,
      radiusMeters,
      resultCount: cached.places.length,
      providers: cached.providers,
      latencyMs: Math.round(performance.now() - startedAt),
      outcome: cached.places.length > 0 ? "success" : "zero_results",
      fallbackUsed: false,
      usedCache: true,
    });
    return json({
      provider: cached.providers.includes("here") && cached.providers.includes("osm")
        ? "here+osm"
        : cached.providers[0] ?? "cache",
      providers: cached.providers,
      capability: "available",
      cacheHit: true,
      places: cached.places,
    });
  }

  const herePromise = apiKey
    ? browseHere(body.center, radiusMeters, categories, apiKey, localeTag)
    : Promise.reject(new Error("HERE_NOT_CONFIGURED"));

  const [hereResult, osmResult] = await Promise.allSettled([
    herePromise,
    browseOsm(body.center, radiusMeters, categories, localeTag),
  ]);

  const herePlaces = hereResult.status === "fulfilled" ? hereResult.value : [];
  const osmPlaces = osmResult.status === "fulfilled" ? osmResult.value : [];

  if (herePlaces.length === 0 && osmPlaces.length === 0) {
    const hereError = hereResult.status === "rejected" ? String(hereResult.reason) : "";
    const osmError = osmResult.status === "rejected" ? String(osmResult.reason) : "";
    if (hereError.includes("RATE_LIMITED")) {
      return json({ error: "rate_limited", providers: ["here", "osm"] }, 429);
    }
    if (hereResult.status === "rejected" && osmResult.status === "rejected") {
      console.error(JSON.stringify({
        event: "places_providers_failed",
        here: apiKey ? hereError.replace(apiKey, "[redacted]") : hereError,
        osm: osmError,
      }));
      void writePlacesTelemetry({
        categories,
        radiusMeters,
        resultCount: 0,
        providers: [],
        latencyMs: Math.round(performance.now() - startedAt),
        outcome: "error",
        fallbackUsed: true,
        usedCache: false,
      });
      return json({ error: "upstream_unavailable", providers: ["here", "osm"] }, 503);
    }
  }

  const places = mergeDeduplicate(herePlaces, osmPlaces);
  const providers = [
    ...(hereResult.status === "fulfilled" ? ["here"] : []),
    ...(osmResult.status === "fulfilled" ? ["osm"] : []),
  ];
  const fallbackUsed = hereResult.status !== "fulfilled" || herePlaces.length === 0;
  void writePlacesTelemetry({
    categories,
    radiusMeters,
    resultCount: places.length,
    providers,
    latencyMs: Math.round(performance.now() - startedAt),
    outcome: places.length > 0 ? "success" : "zero_results",
    fallbackUsed,
    usedCache: false,
  });
  void writePlacesCache(cacheKey, places, providers);
  return json({
    provider: herePlaces.length > 0 && osmPlaces.length > 0 ? "here+osm" : herePlaces.length > 0 ? "here" : "osm",
    providers,
    capability: "available",
    places,
  });
});
