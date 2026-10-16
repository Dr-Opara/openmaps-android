import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type SearchContext = {
  latitude?: number | null;
  longitude?: number | null;
  localeTag?: string | null;
};

type SearchRequest = {
  query?: string;
  context?: SearchContext;
};

type UnifiedResult = {
  id: string;
  name: string;
  displayName: string;
  latitude: number;
  longitude: number;
  category: string | null;
  address: string | null;
  city: string | null;
  state: string | null;
  country: string | null;
  provider: "here" | "nominatim";
  providerConfidence: number;
};

const NOMINATIM = "https://nominatim.openstreetmap.org/search";
const USER_AGENT = "OpenMaps/1.0 (production geocoding gateway; https://openmaps.tech)";
const RESULT_LIMIT = 20;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "private, max-age=30",
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

function firstNonBlank(obj: Record<string, unknown> | undefined, keys: string[]): string | null {
  if (!obj) return null;
  for (const key of keys) {
    const value = obj[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return null;
}

function normalizeText(value: string | null | undefined): string {
  return (value ?? "")
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function queryTokens(query: string): string[] {
  return normalizeText(query).split(" ").filter((token) => token.length > 1);
}

function looksLikeExactAddress(query: string): boolean {
  const q = normalizeText(query);
  if (/^\d+[a-z]?\s+/.test(q)) return true;
  const streetWords = [
    "street", "st", "road", "rd", "avenue", "ave", "boulevard", "blvd",
    "lane", "ln", "drive", "dr", "court", "ct", "way", "highway", "hwy",
    "parkway", "pkwy", "circle", "cir", "terrace", "ter",
  ];
  return streetWords.some((word) => new RegExp("\\b" + word + "\\b").test(q)) &&
    /\d/.test(q);
}

function normalizeNominatim(item: Record<string, unknown>): UnifiedResult {
  const address = (item.address && typeof item.address === "object")
    ? item.address as Record<string, unknown>
    : undefined;
  const displayName = typeof item.display_name === "string" ? item.display_name : "";
  const name = typeof item.name === "string" && item.name.trim()
    ? item.name.trim()
    : displayName.split(",")[0]?.trim() ?? "";
  const house = firstNonBlank(address, ["house_number"]);
  const road = firstNonBlank(address, ["road", "pedestrian", "footway"]);
  const streetAddress = house && road ? house + " " + road : road;
  const category =
    typeof item.type === "string" && item.type.trim()
      ? item.type
      : typeof item.category === "string" && item.category.trim()
        ? item.category
        : null;
  const importance = typeof item.importance === "number" ? item.importance : 0.5;

  return {
    id: "osm:" + String(item.place_id ?? item.osm_id ?? ""),
    name,
    displayName,
    latitude: Number(item.lat),
    longitude: Number(item.lon),
    category,
    address: streetAddress,
    city: firstNonBlank(address, ["city", "town", "village", "hamlet", "municipality"]),
    state: firstNonBlank(address, ["state", "province", "region"]),
    country: firstNonBlank(address, ["country"]),
    provider: "nominatim",
    providerConfidence: Math.max(0.25, Math.min(0.85, importance)),
  };
}

function normalizeHere(item: Record<string, unknown>): UnifiedResult {
  const address = (item.address && typeof item.address === "object")
    ? item.address as Record<string, unknown>
    : undefined;
  const position = (item.position && typeof item.position === "object")
    ? item.position as Record<string, unknown>
    : undefined;
  const categories = Array.isArray(item.categories)
    ? item.categories as Array<Record<string, unknown>>
    : [];
  const firstCategory = categories[0];
  const house = firstNonBlank(address, ["houseNumber"]);
  const street = firstNonBlank(address, ["street"]);
  const streetAddress = house && street ? house + " " + street : street;
  const resultType = firstNonBlank(item, ["resultType"]);
  const scoring = (item.scoring && typeof item.scoring === "object")
    ? item.scoring as Record<string, unknown>
    : undefined;
  const queryScore = typeof scoring?.queryScore === "number" ? scoring.queryScore : 0.8;

  return {
    id: "here:" + String(item.id ?? ""),
    name: typeof item.title === "string" ? item.title : "",
    displayName: firstNonBlank(address, ["label"]) ?? (typeof item.title === "string" ? item.title : ""),
    latitude: Number(position?.lat),
    longitude: Number(position?.lng),
    category: firstNonBlank(firstCategory, ["name"]) ?? resultType,
    address: streetAddress,
    city: firstNonBlank(address, ["city", "district"]),
    state: firstNonBlank(address, ["state"]),
    country: firstNonBlank(address, ["countryName"]),
    provider: "here",
    providerConfidence: Math.max(0.5, Math.min(1.0, queryScore)),
  };
}

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function textRelevance(result: UnifiedResult, query: string): number {
  const q = normalizeText(query);
  const name = normalizeText(result.name);
  const display = normalizeText(result.displayName);
  const address = normalizeText(result.address);
  const city = normalizeText(result.city);
  const tokens = queryTokens(query);

  if (q && (name === q || address === q)) return 1.0;
  if (q && (name.startsWith(q) || address.startsWith(q))) return 0.95;

  const haystack = [name, display, address, city].join(" ");
  const matched = tokens.filter((token) => haystack.includes(token)).length;
  const tokenCoverage = tokens.length === 0 ? 0 : matched / tokens.length;

  let score = 0.35 + (0.55 * tokenCoverage);
  if (display.includes(q)) score += 0.08;
  return Math.max(0, Math.min(1, score));
}

function addressCompleteness(result: UnifiedResult): number {
  let score = 0;
  if (result.address) score += 0.45;
  if (result.city) score += 0.2;
  if (result.state) score += 0.15;
  if (result.country) score += 0.2;
  return score;
}

function proximityScore(result: UnifiedResult, context: SearchContext): number {
  const lat = context.latitude;
  const lon = context.longitude;
  if (
    typeof lat !== "number" || !Number.isFinite(lat) ||
    typeof lon !== "number" || !Number.isFinite(lon)
  ) return 0.5;

  const km = haversineKm(lat, lon, result.latitude, result.longitude);
  if (km <= 1) return 1;
  if (km <= 5) return 0.95;
  if (km <= 20) return 0.85;
  if (km <= 80) return 0.65;
  if (km <= 300) return 0.4;
  return 0.1;
}

function finalScore(result: UnifiedResult, query: string, context: SearchContext): number {
  const exactAddress = looksLikeExactAddress(query);
  const text = textRelevance(result, query);
  const complete = addressCompleteness(result);
  const proximity = proximityScore(result, context);
  const provider = result.providerConfidence;

  // Explicit addresses prioritize textual/address completeness over current proximity so a user can
  // reliably search another city or country. Ambiguous POI queries lean more on nearby context.
  return exactAddress
    ? (text * 0.55) + (complete * 0.25) + (provider * 0.15) + (proximity * 0.05)
    : (text * 0.45) + (proximity * 0.30) + (provider * 0.15) + (complete * 0.10);
}

function samePlace(a: UnifiedResult, b: UnifiedResult): boolean {
  const nameMatch = normalizeText(a.name) === normalizeText(b.name);
  const addressMatch =
    normalizeText(a.address).length > 0 &&
    normalizeText(a.address) === normalizeText(b.address) &&
    normalizeText(a.city) === normalizeText(b.city);

  const distanceMeters = haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) * 1000;
  return distanceMeters <= 80 && (nameMatch || addressMatch);
}

function mergeDeduplicateRank(
  resultSets: UnifiedResult[][],
  query: string,
  context: SearchContext,
): UnifiedResult[] {
  const all = resultSets.flat();
  all.sort((a, b) => finalScore(b, query, context) - finalScore(a, query, context));

  const kept: UnifiedResult[] = [];
  for (const candidate of all) {
    if (!kept.some((existing) => samePlace(existing, candidate))) kept.push(candidate);
    if (kept.length >= RESULT_LIMIT) break;
  }
  return kept;
}

async function searchHere(query: string, context: SearchContext, apiKey: string) {
  const lat = context.latitude;
  const lon = context.longitude;
  const hasPosition =
    typeof lat === "number" && Number.isFinite(lat) && lat >= -90 && lat <= 90 &&
    typeof lon === "number" && Number.isFinite(lon) && lon >= -180 && lon <= 180;

  const useGeocode = looksLikeExactAddress(query);
  const base = useGeocode
    ? "https://geocode.search.hereapi.com/v1/geocode"
    : "https://discover.search.hereapi.com/v1/discover";

  const params = new URLSearchParams({
    q: query,
    limit: String(RESULT_LIMIT),
    apiKey,
  });

  if (!useGeocode && hasPosition) params.set("at", lat + "," + lon);
  if (typeof context.localeTag === "string" && context.localeTag.trim()) {
    params.set("lang", context.localeTag.trim());
  }

  const response = await fetch(base + "?" + params.toString(), {
    headers: { "accept": "application/json" },
    signal: AbortSignal.timeout(6500),
  });
  if (response.status === 429) throw new Error("HERE_RATE_LIMITED");
  if (!response.ok) throw new Error("HERE_" + response.status);

  const body = await response.json() as Record<string, unknown>;
  const items = Array.isArray(body.items) ? body.items as Array<Record<string, unknown>> : [];
  return items
    .map(normalizeHere)
    .filter((item) => Number.isFinite(item.latitude) && Number.isFinite(item.longitude));
}

async function searchNominatim(query: string, context: SearchContext) {
  const params = new URLSearchParams({
    q: query,
    format: "jsonv2",
    addressdetails: "1",
    namedetails: "1",
    dedupe: "1",
    limit: String(RESULT_LIMIT),
  });

  const lat = context.latitude;
  const lon = context.longitude;
  if (
    typeof lat === "number" && Number.isFinite(lat) && lat >= -90 && lat <= 90 &&
    typeof lon === "number" && Number.isFinite(lon) && lon >= -180 && lon <= 180
  ) {
    const delta = 0.75;
    params.set("viewbox", [lon - delta, lat + delta, lon + delta, lat - delta].join(","));
    params.set("bounded", "0");
  }
  if (typeof context.localeTag === "string" && context.localeTag.trim()) {
    params.set("accept-language", context.localeTag.trim());
  }

  const response = await fetch(NOMINATIM + "?" + params.toString(), {
    headers: {
      "accept": "application/json",
      "user-agent": USER_AGENT,
    },
    signal: AbortSignal.timeout(7000),
  });
  if (response.status === 429) throw new Error("NOMINATIM_RATE_LIMITED");
  if (!response.ok) throw new Error("NOMINATIM_" + response.status);

  const raw = await response.json();
  if (!Array.isArray(raw)) throw new Error("NOMINATIM_MALFORMED");
  return raw
    .map((item) => normalizeNominatim(item as Record<string, unknown>))
    .filter((item) => Number.isFinite(item.latitude) && Number.isFinite(item.longitude));
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

  let body: SearchRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const query = body.query?.trim() ?? "";
  if (query.length < 2 || query.length > 256) {
    return json({ error: "invalid_query" }, 400);
  }

  const context = body.context ?? {};
  const hereApiKey = Deno.env.get("HERE_API_KEY")?.trim();

  const searches: Promise<UnifiedResult[]>[] = [searchNominatim(query, context)];
  if (hereApiKey) searches.unshift(searchHere(query, context, hereApiKey));

  const settled = await Promise.allSettled(searches);
  const successful = settled
    .filter((item): item is PromiseFulfilledResult<UnifiedResult[]> => item.status === "fulfilled")
    .map((item) => item.value);

  if (successful.length === 0) {
    const rateLimited = settled.some(
      (item) => item.status === "rejected" && String(item.reason).includes("RATE_LIMITED"),
    );
    return json({ error: rateLimited ? "rate_limited" : "upstream_unavailable" }, rateLimited ? 429 : 503);
  }

  const results = mergeDeduplicateRank(successful, query, context);
  const providers = settled
    .map((item, index) => ({ item, provider: hereApiKey && index === 0 ? "here" : "nominatim" }))
    .filter(({ item }) => item.status === "fulfilled")
    .map(({ provider }) => provider);

  return json({
    providers,
    queryType: looksLikeExactAddress(query) ? "address" : "general",
    results: results.map(({ provider: _provider, providerConfidence: _confidence, ...result }) => result),
  });
});
