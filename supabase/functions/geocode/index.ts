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

function normalizeNominatim(item: Record<string, unknown>) {
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

  return {
    id: String(item.place_id ?? item.osm_id ?? ""),
    name,
    displayName,
    latitude: Number(item.lat),
    longitude: Number(item.lon),
    category,
    address: streetAddress,
    city: firstNonBlank(address, ["city", "town", "village", "hamlet", "municipality"]),
    state: firstNonBlank(address, ["state", "province", "region"]),
    country: firstNonBlank(address, ["country"]),
  };
}

function normalizeHere(item: Record<string, unknown>) {
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

  return {
    id: String(item.id ?? ""),
    name: typeof item.title === "string" ? item.title : "",
    displayName: firstNonBlank(address, ["label"]) ?? (typeof item.title === "string" ? item.title : ""),
    latitude: Number(position?.lat),
    longitude: Number(position?.lng),
    category: firstNonBlank(firstCategory, ["name"]) ?? firstNonBlank(item, ["resultType"]),
    address: streetAddress,
    city: firstNonBlank(address, ["city", "district"]),
    state: firstNonBlank(address, ["state"]),
    country: firstNonBlank(address, ["countryName"]),
  };
}

async function searchHere(query: string, context: SearchContext, apiKey: string) {
  const lat = context.latitude;
  const lon = context.longitude;
  const hasPosition =
    typeof lat === "number" && Number.isFinite(lat) && lat >= -90 && lat <= 90 &&
    typeof lon === "number" && Number.isFinite(lon) && lon >= -180 && lon <= 180;

  const base = hasPosition
    ? "https://discover.search.hereapi.com/v1/discover"
    : "https://geocode.search.hereapi.com/v1/geocode";

  const params = new URLSearchParams({
    q: query,
    limit: String(RESULT_LIMIT),
    apiKey,
  });

  if (hasPosition) params.set("at", lat + "," + lon);
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

  if (hereApiKey) {
    try {
      const hereResults = await searchHere(query, context, hereApiKey);
      if (hereResults.length > 0) {
        return json({ provider: "here", results: hereResults });
      }
    } catch {
      // Provider failover is intentional. Do not leak provider credentials/errors to clients.
    }
  }

  try {
    const osmResults = await searchNominatim(query, context);
    return json({ provider: "nominatim", results: osmResults });
  } catch (error) {
    const detail = error instanceof Error ? error.message : "upstream_unavailable";
    const status = detail.includes("RATE_LIMITED") ? 429 : 503;
    return json({ error: "upstream_unavailable", detail }, status);
  }
});
