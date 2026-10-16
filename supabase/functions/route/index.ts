import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type RouteRequest = {
  origin?: Coordinate;
  destination?: Coordinate;
  localeTag?: string | null;
};

type UnifiedManeuver = {
  instruction: string;
  verbalInstruction: string | null;
  type: string;
  streetName: string | null;
  distanceMeters: number;
  beginShapeIndex: number;
  endShapeIndex: number;
  exitSign: string | null;
  signpost: string | null;
};

type UnifiedRoute = {
  id: string | null;
  label: string | null;
  geometry: Coordinate[];
  distanceMeters: number;
  durationSeconds: number;
  baseDurationSeconds: number | null;
  typicalDurationSeconds: number | null;
  trafficDelaySeconds: number | null;
  maneuvers: UnifiedManeuver[];
};

const FLEX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
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

async function consumeNavigationRateLimit(req: Request): Promise<boolean> {
  const base = supabaseUrl();
  const key = serviceRoleKey();
  if (!base || !key) return true;

  try {
    const clientHash = await sha256Hex(clientAddress(req));
    const now = new Date();
    now.setSeconds(0, 0);
    const response = await fetch(base + "/rest/v1/rpc/consume_navigation_rate_limit", {
      method: "POST",
      headers: {
        apikey: key,
        authorization: "Bearer " + key,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        p_client_hash: clientHash,
        p_endpoint: "route",
        p_bucket_start: now.toISOString(),
        p_limit: 30,
      }),
    });
    if (!response.ok) return true;
    return (await response.json()) === true;
  } catch {
    return true;
  }
}

function serviceRoleKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim() || null;
}

function supabaseUrl(): string | null {
  return Deno.env.get("SUPABASE_URL")?.trim() || null;
}

async function writeRoutingTelemetry(event: {
  outcome: "success" | "error";
  latencyMs: number;
  alternativesCount: number;
  trafficAware: boolean;
  trafficDelaySeconds: number | null;
  provider: string | null;
  routeDistanceMeters: number | null;
  routeDurationSeconds: number | null;
}): Promise<void> {
  const base = supabaseUrl();
  const key = serviceRoleKey();
  if (!base || !key) return;

  try {
    await fetch(base + "/rest/v1/routing_telemetry", {
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
        alternatives_count: event.alternativesCount,
        traffic_aware: event.trafficAware,
        traffic_delay_seconds: event.trafficDelaySeconds,
        provider: event.provider,
        route_distance_meters: event.routeDistanceMeters,
        route_duration_seconds: event.routeDurationSeconds,
      }),
    });
  } catch {
    // Telemetry must never block routing.
  }
}

function validCoordinate(value: Coordinate | undefined): value is Coordinate {
  return !!value &&
    Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function decodeUnsigned(encoded: string, cursor: { index: number }): number {
  let result = 0;
  let shift = 0;
  while (cursor.index < encoded.length) {
    const value = FLEX_ALPHABET.indexOf(encoded[cursor.index++]);
    if (value < 0) throw new Error("invalid_flexible_polyline");
    result |= (value & 0x1f) << shift;
    if ((value & 0x20) === 0) return result;
    shift += 5;
    if (shift > 53) throw new Error("flexible_polyline_overflow");
  }
  throw new Error("truncated_flexible_polyline");
}

function decodeSigned(encoded: string, cursor: { index: number }): number {
  const value = decodeUnsigned(encoded, cursor);
  return (value & 1) ? -((value >> 1) + 1) : (value >> 1);
}

function decodeFlexiblePolyline(encoded: string): Coordinate[] {
  const cursor = { index: 0 };
  const version = decodeUnsigned(encoded, cursor);
  if (version !== 1) throw new Error("unsupported_flexible_polyline_version");

  const header = decodeUnsigned(encoded, cursor);
  const precision = header & 15;
  const thirdDim = (header >> 4) & 7;
  const thirdDimPrecision = (header >> 7) & 15;
  const factor = 10 ** precision;
  const thirdFactor = 10 ** thirdDimPrecision;

  let lat = 0;
  let lng = 0;
  let third = 0;
  const points: Coordinate[] = [];

  while (cursor.index < encoded.length) {
    lat += decodeSigned(encoded, cursor);
    lng += decodeSigned(encoded, cursor);
    if (thirdDim !== 0) third += decodeSigned(encoded, cursor);
    points.push({
      latitude: lat / factor,
      longitude: lng / factor,
    });
    void third;
    void thirdFactor;
  }
  return points;
}

function maneuverType(action: string, direction?: string): string {
  const dir = direction ?? "";
  if (action === "depart") return "Start";
  if (action === "arrive") return "Destination";
  if (action === "roundaboutEnter" || action === "roundaboutExit") return "Roundabout";
  if (action === "ramp" || action === "exit") return "Ramp";
  if (action === "merge") return "Merge";
  if (action === "continue") return "Continue";
  if (action === "uTurn") return "UTurn";
  if (action === "turn") {
    if (dir.includes("left")) {
      if (dir.includes("slight")) return "SlightLeft";
      if (dir.includes("sharp")) return "SharpLeft";
      return "TurnLeft";
    }
    if (dir.includes("right")) {
      if (dir.includes("slight")) return "SlightRight";
      if (dir.includes("sharp")) return "SharpRight";
      return "TurnRight";
    }
  }
  return "Unknown";
}

function firstRoadName(action: Record<string, unknown>): string | null {
  const nextRoad = action.nextRoad;
  if (!nextRoad || typeof nextRoad !== "object") return null;
  const names = (nextRoad as Record<string, unknown>).name;
  if (!Array.isArray(names) || names.length === 0) return null;
  const first = names[0];
  return first && typeof first === "object" && typeof (first as Record<string, unknown>).value === "string"
    ? String((first as Record<string, unknown>).value)
    : null;
}

function firstDisplayString(value: unknown): string | null {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = firstDisplayString(item);
      if (found) return found;
    }
    return null;
  }
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    for (const key of ["text", "value", "name", "number", "destination", "toward"]) {
      const found = firstDisplayString(record[key]);
      if (found) return found;
    }
    for (const child of Object.values(record)) {
      const found = firstDisplayString(child);
      if (found) return found;
    }
  }
  return null;
}

function routeLabel(route: Record<string, unknown>): string | null {
  const labels = route.routeLabels;
  if (!Array.isArray(labels)) return null;
  const values = labels
    .map((item) => firstDisplayString(item))
    .filter((item): item is string => Boolean(item));
  return values.length > 0 ? "via " + values.slice(0, 2).join(" / ") : null;
}

function normalizeHereRoute(route: Record<string, unknown>): UnifiedRoute {
  const sections = Array.isArray(route.sections)
    ? route.sections as Array<Record<string, unknown>>
    : [];
  if (sections.length === 0) throw new Error("no_route");

  const geometry: Coordinate[] = [];
  const maneuvers: UnifiedManeuver[] = [];
  let distanceMeters = 0;
  let durationSeconds = 0;
  let baseDurationSeconds = 0;
  let typicalDurationSeconds = 0;
  let hasBase = false;
  let hasTypical = false;

  for (const section of sections) {
    const sectionOffset = geometry.length;
    const encoded = typeof section.polyline === "string" ? section.polyline : "";
    const sectionGeometry = decodeFlexiblePolyline(encoded);
    geometry.push(...sectionGeometry);

    const summary = section.summary && typeof section.summary === "object"
      ? section.summary as Record<string, unknown>
      : {};
    distanceMeters += Number(summary.length ?? 0);
    durationSeconds += Number(summary.duration ?? 0);

    if (typeof summary.baseDuration === "number") {
      baseDurationSeconds += summary.baseDuration;
      hasBase = true;
    } else if (typeof summary.duration === "number") {
      baseDurationSeconds += summary.duration;
    }

    if (typeof summary.typicalDuration === "number") {
      typicalDurationSeconds += summary.typicalDuration;
      hasTypical = true;
    } else if (typeof summary.duration === "number") {
      typicalDurationSeconds += summary.duration;
    }

    const actions = Array.isArray(section.turnByTurnActions)
      ? section.turnByTurnActions as Array<Record<string, unknown>>
      : Array.isArray(section.actions)
        ? section.actions as Array<Record<string, unknown>>
        : [];

    for (let i = 0; i < actions.length; i++) {
      const action = actions[i];
      const offset = Number(action.offset ?? 0);
      const nextOffset = i + 1 < actions.length
        ? Number(actions[i + 1].offset ?? sectionGeometry.length - 1)
        : Math.max(0, sectionGeometry.length - 1);
      const instruction = typeof action.instruction === "string"
        ? action.instruction
        : String(action.action ?? "Continue");

      maneuvers.push({
        instruction,
        verbalInstruction: instruction,
        type: maneuverType(String(action.action ?? ""), typeof action.direction === "string" ? action.direction : undefined),
        streetName: firstRoadName(action),
        distanceMeters: Number(action.length ?? 0),
        beginShapeIndex: sectionOffset + Math.max(0, offset),
        endShapeIndex: sectionOffset + Math.max(0, nextOffset),
        exitSign: firstDisplayString(action.exitSign),
        signpost: firstDisplayString(action.signpost ?? action.signPost),
      });
    }
  }

  if (geometry.length === 0) throw new Error("no_route");

  const base = hasBase ? baseDurationSeconds : null;
  const typical = hasTypical ? typicalDurationSeconds : null;
  const delay = base == null ? null : Math.max(0, durationSeconds - base);

  return {
    id: typeof route.id === "string" ? route.id : null,
    label: routeLabel(route),
    geometry,
    distanceMeters,
    durationSeconds,
    baseDurationSeconds: base,
    typicalDurationSeconds: typical,
    trafficDelaySeconds: delay,
    maneuvers,
  };
}

async function routeHere(
  origin: Coordinate,
  destination: Coordinate,
  localeTag: string | null | undefined,
  apiKey: string,
): Promise<UnifiedRoute[]> {
  const params = new URLSearchParams({
    transportMode: "car",
    origin: origin.latitude + "," + origin.longitude,
    destination: destination.latitude + "," + destination.longitude,
    alternatives: "2",
    return: "polyline,summary,turnByTurnActions,typicalDuration,routeLabels",
    apiKey,
  });
  if (localeTag?.trim()) params.set("lang", localeTag.trim());

  const response = await fetch("https://router.hereapi.com/v8/routes?" + params.toString(), {
    headers: { "accept": "application/json" },
    signal: AbortSignal.timeout(8500),
  });

  if (response.status === 429) throw new Error("rate_limited");
  if (!response.ok) throw new Error("here_" + response.status);

  const body = await response.json() as Record<string, unknown>;
  const routes = Array.isArray(body.routes) ? body.routes as Array<Record<string, unknown>> : [];
  if (routes.length === 0) throw new Error("no_route");
  return routes.map(normalizeHereRoute);
}

Deno.serve(async (req: Request) => {
  const startedAt = performance.now();
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);
  if (!(await consumeNavigationRateLimit(req))) return json({ error: "rate_limited" }, 429);

  let body: RouteRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!validCoordinate(body.origin) || !validCoordinate(body.destination)) {
    return json({ error: "invalid_coordinates" }, 400);
  }

  const hereApiKey = Deno.env.get("HERE_API_KEY")?.trim();
  if (!hereApiKey) {
    return json({ error: "commercial_routing_provider_not_configured" }, 503);
  }

  try {
    const routes = await routeHere(body.origin, body.destination, body.localeTag, hereApiKey);
    const primary = routes[0];
    void writeRoutingTelemetry({
      outcome: "success",
      latencyMs: Math.round(performance.now() - startedAt),
      alternativesCount: Math.max(0, routes.length - 1),
      trafficAware: true,
      trafficDelaySeconds: primary.trafficDelaySeconds,
      provider: "here",
      routeDistanceMeters: primary.distanceMeters,
      routeDurationSeconds: primary.durationSeconds,
    });
    return json({
      provider: "here",
      trafficAware: true,
      route: primary,
      alternatives: routes.slice(1),
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "routing_failed";
    void writeRoutingTelemetry({
      outcome: "error",
      latencyMs: Math.round(performance.now() - startedAt),
      alternativesCount: 0,
      trafficAware: false,
      trafficDelaySeconds: null,
      provider: "here",
      routeDistanceMeters: null,
      routeDurationSeconds: null,
    });
    if (message === "rate_limited") return json({ error: "rate_limited" }, 429);
    if (message === "no_route") return json({ error: "no_route" }, 404);
    return json({ error: "routing_failed" }, 503);
  }
});
