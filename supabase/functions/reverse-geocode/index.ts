import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type ReverseRequest = {
  latitude?: number;
  longitude?: number;
  localeTag?: string | null;
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
};

const NOMINATIM = "https://nominatim.openstreetmap.org/reverse";
const USER_AGENT = "OpenMaps/1.0 (production reverse geocoding gateway; https://openmaps.tech)";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "private, max-age=60",
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

function normalizeHere(item: Record<string, unknown>): UnifiedResult {
  const address = (item.address && typeof item.address === "object")
    ? item.address as Record<string, unknown>
    : undefined;
  const position = (item.position && typeof item.position === "object")
    ? item.position as Record<string, unknown>
    : undefined;
  const house = firstNonBlank(address, ["houseNumber"]);
  const street = firstNonBlank(address, ["street"]);
  const streetAddress = house && street ? house + " " + street : street;

  return {
    id: "here:" + String(item.id ?? ""),
    name: typeof item.title === "string" ? item.title : (firstNonBlank(address, ["label"]) ?? ""),
    displayName: firstNonBlank(address, ["label"]) ?? (typeof item.title === "string" ? item.title : ""),
    latitude: Number(position?.lat),
    longitude: Number(position?.lng),
    category: firstNonBlank(item, ["resultType"]),
    address: streetAddress,
    city: firstNonBlank(address, ["city", "district"]),
    state: firstNonBlank(address, ["state"]),
    country: firstNonBlank(address, ["countryName"]),
  };
}

function normalizeNominatim(item: Record<string, unknown>): UnifiedResult {
  const address = (item.address && typeof item.address === "object")
    ? item.address as Record<string, unknown>
    : undefined;
  const displayName = typeof item.display_name === "string" ? item.display_name : "";
  const house = firstNonBlank(address, ["house_number"]);
  const road = firstNonBlank(address, ["road", "pedestrian", "footway"]);
  const streetAddress = house && road ? house + " " + road : road;
  const name = typeof item.name === "string" && item.name.trim()
    ? item.name.trim()
    : streetAddress ?? displayName.split(",")[0]?.trim() ?? "";

  return {
    id: "osm:" + String(item.place_id ?? item.osm_id ?? ""),
    name,
    displayName,
    latitude: Number(item.lat),
    longitude: Number(item.lon),
    category: typeof item.type === "string" ? item.type : null,
    address: streetAddress,
    city: firstNonBlank(address, ["city", "town", "village", "hamlet", "municipality"]),
    state: firstNonBlank(address, ["state", "province", "region"]),
    country: firstNonBlank(address, ["country"]),
  };
}

async function reverseHere(latitude: number, longitude: number, localeTag: string | null | undefined, apiKey: string) {
  const params = new URLSearchParams({
    at: latitude + "," + longitude,
    limit: "1",
    apiKey,
  });
  if (localeTag?.trim()) params.set("lang", localeTag.trim());

  const response = await fetch("https://revgeocode.search.hereapi.com/v1/revgeocode?" + params.toString(), {
    headers: { "accept": "application/json" },
    signal: AbortSignal.timeout(6500),
  });
  if (!response.ok) throw new Error("HERE_" + response.status);
  const body = await response.json() as Record<string, unknown>;
  const items = Array.isArray(body.items) ? body.items as Array<Record<string, unknown>> : [];
  return items.length > 0 ? normalizeHere(items[0]) : null;
}

async function reverseNominatim(latitude: number, longitude: number, localeTag: string | null | undefined) {
  const params = new URLSearchParams({
    lat: String(latitude),
    lon: String(longitude),
    format: "jsonv2",
    addressdetails: "1",
    zoom: "18",
  });
  if (localeTag?.trim()) params.set("accept-language", localeTag.trim());

  const response = await fetch(NOMINATIM + "?" + params.toString(), {
    headers: {
      "accept": "application/json",
      "user-agent": USER_AGENT,
    },
    signal: AbortSignal.timeout(7000),
  });
  if (!response.ok) throw new Error("NOMINATIM_" + response.status);
  const body = await response.json() as Record<string, unknown>;
  if (body.error) return null;
  return normalizeNominatim(body);
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

  let body: ReverseRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const latitude = body.latitude;
  const longitude = body.longitude;
  if (
    typeof latitude !== "number" || !Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
    typeof longitude !== "number" || !Number.isFinite(longitude) || longitude < -180 || longitude > 180
  ) {
    return json({ error: "invalid_coordinates" }, 400);
  }

  const hereApiKey = Deno.env.get("HERE_API_KEY")?.trim();
  if (hereApiKey) {
    try {
      const result = await reverseHere(latitude, longitude, body.localeTag, hereApiKey);
      if (result) return json({ provider: "here", result });
    } catch {
      // Intentional fallback to OSM.
    }
  }

  try {
    const result = await reverseNominatim(latitude, longitude, body.localeTag);
    return json({ provider: "nominatim", result });
  } catch {
    return json({ error: "upstream_unavailable" }, 503);
  }
});
