import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type PlaceCategory =
  | "Restaurant" | "Coffee" | "Hotel" | "Grocery" | "Pharmacy" | "Parking"
  | "Gas" | "EvCharging" | "Bank" | "Atm" | "Convenience" | "Medical"
  | "Entertainment" | "Shopping";

type PlacesRequest = {
  center?: Coordinate;
  radiusMeters?: number;
  categories?: PlaceCategory[];
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

  return {
    id: "here:" + String(item.id ?? ""),
    name: firstString(item.title) ?? category,
    category,
    latitude,
    longitude,
    address: firstString(address?.label),
    distanceMeters: typeof item.distance === "number" ? item.distance : null,
    openNow: null,
    metadata: {
      parkingType: null,
      isPublicAccess: null,
      feeApplies: null,
      brand,
      connectors: [],
    },
  };
}

async function browseHere(
  center: Coordinate,
  radiusMeters: number,
  categories: PlaceCategory[],
  apiKey: string,
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

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

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
  const apiKey = Deno.env.get("HERE_API_KEY")?.trim();
  if (!apiKey) return json({ error: "provider_not_configured", provider: "here" }, 503);

  try {
    const places = await browseHere(body.center, radiusMeters, categories, apiKey);
    return json({ provider: "here", capability: "available", places });
  } catch (error) {
    const message = String(error);
    if (message.includes("RATE_LIMITED")) {
      return json({ error: "rate_limited", provider: "here" }, 429);
    }
    console.error(JSON.stringify({
      event: "places_provider_failed",
      provider: "here",
      error: message.replace(apiKey, "[redacted]"),
    }));
    return json({ error: "upstream_unavailable", provider: "here" }, 503);
  }
});
