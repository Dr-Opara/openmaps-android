import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type RequestBody = {
  routeGeometry?: Coordinate[];
  corridorMeters?: number;
  localeTag?: string | null;
};

type Incident = {
  id: string;
  type: string;
  latitude: number;
  longitude: number;
  severity: string;
  title: string;
  description: string | null;
  startTimeMillis: number | null;
  endTimeMillis: number | null;
  metadata: Record<string, string>;
};

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

function serviceRoleKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim() || null;
}

function supabaseUrl(): string | null {
  return Deno.env.get("SUPABASE_URL")?.trim() || null;
}

async function writeTrafficTelemetry(event: {
  outcome: "success" | "error";
  latencyMs: number;
  incidentsCount: number;
  routePoints: number;
}): Promise<void> {
  const base = supabaseUrl();
  const key = serviceRoleKey();
  if (!base || !key) return;
  try {
    await fetch(base + "/rest/v1/traffic_telemetry", {
      method: "POST",
      headers: {
        apikey: key,
        authorization: "Bearer " + key,
        "content-type": "application/json",
        prefer: "return=minimal",
      },
      body: JSON.stringify({
        outcome: event.outcome,
        latency_ms: event.latencyMs,
        incidents_count: event.incidentsCount,
        provider: "here",
        route_points: event.routePoints,
      }),
    });
  } catch {
    // Telemetry is best-effort.
  }
}

function validCoordinate(value: Coordinate): boolean {
  return Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function firstText(value: unknown): string | null {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = firstText(item);
      if (found) return found;
    }
    return null;
  }
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    for (const key of ["value", "text", "description", "summary", "name"]) {
      const found = firstText(record[key]);
      if (found) return found;
    }
  }
  return null;
}

function coordinatesFromShape(value: unknown, output: Coordinate[] = []): Coordinate[] {
  if (Array.isArray(value)) {
    for (const item of value) coordinatesFromShape(item, output);
    return output;
  }
  if (!value || typeof value !== "object") return output;

  const record = value as Record<string, unknown>;
  const lat = typeof record.lat === "number" ? record.lat
    : typeof record.latitude === "number" ? record.latitude : null;
  const lng = typeof record.lng === "number" ? record.lng
    : typeof record.lon === "number" ? record.lon
    : typeof record.longitude === "number" ? record.longitude : null;

  if (lat != null && lng != null && validCoordinate({ latitude: lat, longitude: lng })) {
    output.push({ latitude: lat, longitude: lng });
  }

  for (const child of Object.values(record)) {
    if (child && typeof child === "object") coordinatesFromShape(child, output);
  }
  return output;
}

function typeFor(details: Record<string, unknown>): string {
  if (details.roadClosed === true) return "Closure";
  const raw = String(details.type ?? "").toLowerCase();
  if (raw.includes("accident") || raw.includes("crash")) return "Crash";
  if (raw.includes("construction") || raw.includes("roadwork")) return "Construction";
  if (raw.includes("congestion") || raw.includes("jam")) return "Congestion";
  if (raw.includes("flood")) return "Flooding";
  if (raw.includes("weather")) return "Other";
  return "Other";
}

function severityFor(details: Record<string, unknown>): string {
  const raw = String(details.criticality ?? "").toLowerCase();
  if (raw === "critical") return "Severe";
  if (raw === "major") return "High";
  if (raw === "minor") return "Moderate";
  return "Low";
}

function timestampMillis(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function normalizeIncident(item: Record<string, unknown>): Incident | null {
  const details = item.incidentDetails && typeof item.incidentDetails === "object"
    ? item.incidentDetails as Record<string, unknown>
    : null;
  const location = item.location && typeof item.location === "object"
    ? item.location as Record<string, unknown>
    : null;
  if (!details || !location) return null;

  const points = coordinatesFromShape(location.shape);
  const point = points[0];
  if (!point) return null;

  const type = typeFor(details);
  const title =
    firstText(details.summary) ??
    firstText(details.typeDescription) ??
    (type === "Closure" ? "Road closed" : "Traffic incident");

  return {
    id: String(details.originalId ?? details.id ?? ""),
    type,
    latitude: point.latitude,
    longitude: point.longitude,
    severity: severityFor(details),
    title,
    description: firstText(details.description),
    startTimeMillis: timestampMillis(details.startTime),
    endTimeMillis: timestampMillis(details.endTime),
    metadata: {
      source: "HERE Traffic",
      roadClosed: String(details.roadClosed === true),
      criticality: String(details.criticality ?? ""),
      hereType: String(details.type ?? ""),
    },
  };
}

Deno.serve(async (req: Request) => {
  const startedAt = performance.now();
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

  let body: RequestBody;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const geometry = Array.isArray(body.routeGeometry)
    ? body.routeGeometry.filter(validCoordinate)
    : [];
  if (geometry.length < 2) return json({ incidents: [] });

  const hereApiKey = Deno.env.get("HERE_API_KEY")?.trim();
  if (!hereApiKey) return json({ error: "traffic_provider_not_configured" }, 503);

  let west = 180;
  let east = -180;
  let south = 90;
  let north = -90;
  for (const point of geometry) {
    west = Math.min(west, point.longitude);
    east = Math.max(east, point.longitude);
    south = Math.min(south, point.latitude);
    north = Math.max(north, point.latitude);
  }

  const corridorMeters = Math.max(50, Math.min(5000, body.corridorMeters ?? 500));
  const midLat = (south + north) / 2;
  const latPad = corridorMeters / 111_320;
  const lonPad = corridorMeters / (111_320 * Math.max(0.2, Math.cos(midLat * Math.PI / 180)));

  const params = new URLSearchParams({
    in: "bbox:" + [west - lonPad, south - latPad, east + lonPad, north + latPad].join(","),
    locationReferencing: "shape",
    apiKey: hereApiKey,
  });
  if (body.localeTag?.trim()) params.set("lang", body.localeTag.trim());

  try {
    const response = await fetch(
      "https://data.traffic.hereapi.com/v7/incidents?" + params.toString(),
      {
        headers: { "accept": "application/json" },
        signal: AbortSignal.timeout(7000),
      },
    );
    if (response.status === 429) return json({ error: "rate_limited" }, 429);
    if (!response.ok) return json({ error: "traffic_upstream_" + response.status }, 503);

    const payload = await response.json() as Record<string, unknown>;
    const raw = Array.isArray(payload.results) ? payload.results as Array<Record<string, unknown>> : [];
    const incidents = raw
      .map(normalizeIncident)
      .filter((item): item is Incident => item != null && item.id.length > 0);

    void writeTrafficTelemetry({
      outcome: "success",
      latencyMs: Math.round(performance.now() - startedAt),
      incidentsCount: incidents.length,
      routePoints: geometry.length,
    });
    return json({
      provider: "here",
      sourceUpdated: payload.sourceUpdated ?? null,
      incidents,
    });
  } catch {
    void writeTrafficTelemetry({
      outcome: "error",
      latencyMs: Math.round(performance.now() - startedAt),
      incidentsCount: 0,
      routePoints: geometry.length,
    });
    return json({ error: "traffic_upstream_unavailable" }, 503);
  }
});
