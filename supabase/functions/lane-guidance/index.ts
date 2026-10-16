import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type LaneRequest = {
  trace?: Coordinate[];
  maneuverType?: string | null;
};

type LaneDirection =
  | "Left"
  | "SlightLeft"
  | "Straight"
  | "SlightRight"
  | "Right"
  | "UTurn"
  | "Unknown";

type Lane = {
  directions: LaneDirection[];
  recommended: boolean;
};

type Guidance = {
  lanes: Lane[];
  recommendedLaneIndexes: number[];
  maneuverRelation: string | null;
  source: "osm" | "mapbox" | "osm+mapbox";
  confidence: "high" | "medium";
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
        p_endpoint: "lane-guidance",
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

function validCoordinate(value: Coordinate): boolean {
  return Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function radians(value: number): number {
  return value * Math.PI / 180;
}

function metersBetween(a: Coordinate, b: Coordinate): number {
  const earth = 6371000;
  const dLat = radians(b.latitude - a.latitude);
  const dLon = radians(b.longitude - a.longitude);
  const lat1 = radians(a.latitude);
  const lat2 = radians(b.latitude);
  const h = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * earth * Math.asin(Math.min(1, Math.sqrt(h)));
}

function bearing(a: Coordinate, b: Coordinate): number {
  const lat1 = radians(a.latitude);
  const lat2 = radians(b.latitude);
  const dLon = radians(b.longitude - a.longitude);
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

function bearingDelta(a: number, b: number): number {
  return Math.abs(((a - b + 540) % 360) - 180);
}

function approachPoint(trace: Coordinate[], targetMeters = 60): Coordinate {
  let accumulated = 0;
  for (let i = trace.length - 1; i > 0; i--) {
    accumulated += metersBetween(trace[i], trace[i - 1]);
    if (accumulated >= targetMeters) return trace[i - 1];
  }
  return trace[0];
}

function directionFromText(raw: string): LaneDirection {
  const text = raw.trim().toLowerCase().replaceAll("_", " ").replaceAll("-", " ");
  if (text === "left" || text === "sharp left") return "Left";
  if (text === "slight left") return "SlightLeft";
  if (text === "through" || text === "straight" || text === "none") return "Straight";
  if (text === "slight right") return "SlightRight";
  if (text === "right" || text === "sharp right") return "Right";
  if (text === "reverse" || text === "uturn" || text === "u turn") return "UTurn";
  return "Unknown";
}

function compatible(direction: LaneDirection, maneuverType: string | null | undefined): boolean {
  switch (maneuverType) {
    case "TurnLeft":
    case "SharpLeft":
      return direction === "Left" || direction === "SlightLeft";
    case "SlightLeft":
      return direction === "Left" || direction === "SlightLeft" || direction === "Straight";
    case "TurnRight":
    case "SharpRight":
    case "Ramp":
      return direction === "Right" || direction === "SlightRight";
    case "SlightRight":
      return direction === "Right" || direction === "SlightRight" || direction === "Straight";
    case "UTurn":
      return direction === "UTurn" || direction === "Left";
    case "Continue":
    case "Merge":
    case "Start":
      return direction === "Straight" || direction === "SlightLeft" || direction === "SlightRight";
    default:
      return false;
  }
}

function buildGuidance(
  lanes: Array<{ directions: LaneDirection[]; recommended?: boolean }>,
  maneuverType: string | null | undefined,
  source: Guidance["source"],
  confidence: Guidance["confidence"],
): Guidance | null {
  const normalized = lanes.map((lane) => {
    const directions = lane.directions.length > 0 ? Array.from(new Set(lane.directions)) : ["Unknown" as LaneDirection];
    const recommended = lane.recommended === true ||
      directions.some((direction) => compatible(direction, maneuverType));
    return { directions, recommended };
  });

  const recommendedLaneIndexes = normalized
    .map((lane, index) => lane.recommended ? index : -1)
    .filter((index) => index >= 0);

  if (normalized.length === 0 || recommendedLaneIndexes.length === 0) return null;
  return {
    lanes: normalized,
    recommendedLaneIndexes,
    maneuverRelation: maneuverType ?? null,
    source,
    confidence,
  };
}

function parseOsmTurnLanes(raw: string, maneuverType: string | null | undefined): Guidance | null {
  const parts = raw.split("|");
  if (parts.length < 2) return null;

  const lanes = parts.map((part) => {
    const directions = part
      .split(";")
      .map(directionFromText)
      .filter((direction) => direction !== "Unknown");
    return { directions };
  });
  return buildGuidance(lanes, maneuverType, "osm", "medium");
}

type OsmGeometryPoint = { lat?: number; lon?: number };
type OsmElement = {
  id?: number;
  center?: { lat?: number; lon?: number };
  geometry?: OsmGeometryPoint[];
  tags?: Record<string, string>;
};

function inferOsmWayForward(element: OsmElement, trace: Coordinate[]): boolean | null {
  const geometry = Array.isArray(element.geometry) ? element.geometry : [];
  if (geometry.length < 2 || trace.length < 2) return null;
  const approach = approachPoint(trace, 40);
  let bestIndex = -1;
  let bestDistance = Number.POSITIVE_INFINITY;

  for (let i = 0; i < geometry.length - 1; i++) {
    const point = geometry[i];
    if (!Number.isFinite(point.lat) || !Number.isFinite(point.lon)) continue;
    const distance = metersBetween(approach, {
      latitude: Number(point.lat),
      longitude: Number(point.lon),
    });
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = i;
    }
  }

  if (bestIndex < 0) return null;
  const a = geometry[bestIndex];
  const b = geometry[Math.min(bestIndex + 1, geometry.length - 1)];
  if (!Number.isFinite(a.lat) || !Number.isFinite(a.lon) || !Number.isFinite(b.lat) || !Number.isFinite(b.lon)) {
    return null;
  }

  const traceHeading = bearing(trace[Math.max(0, trace.length - 3)], trace[trace.length - 1]);
  const wayHeading = bearing(
    { latitude: Number(a.lat), longitude: Number(a.lon) },
    { latitude: Number(b.lat), longitude: Number(b.lon) },
  );
  return bearingDelta(traceHeading, wayHeading) <= 90;
}

async function fetchOsmGuidance(
  trace: Coordinate[],
  maneuverType: string | null | undefined,
): Promise<Guidance | null> {
  const approach = approachPoint(trace);
  const query = `[out:json][timeout:5];
(
  way(around:35,${approach.latitude},${approach.longitude})["highway"]["turn:lanes"];
  way(around:35,${approach.latitude},${approach.longitude})["highway"]["turn:lanes:forward"];
  way(around:35,${approach.latitude},${approach.longitude})["highway"]["turn:lanes:backward"];
);
out tags center geom;`;

  const endpoints = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
  ];

  for (const endpoint of endpoints) {
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded; charset=utf-8" },
        body: "data=" + encodeURIComponent(query),
        signal: AbortSignal.timeout(6500),
      });
      if (!response.ok) continue;
      const payload = await response.json() as { elements?: OsmElement[] };
      const elements = Array.isArray(payload.elements) ? payload.elements : [];
      if (elements.length === 0) return null;

      const ranked = elements
        .map((element) => {
          const center = element.center;
          const distance = center && Number.isFinite(center.lat) && Number.isFinite(center.lon)
            ? metersBetween(approach, { latitude: Number(center.lat), longitude: Number(center.lon) })
            : Number.POSITIVE_INFINITY;
          return { element, distance };
        })
        .sort((a, b) => a.distance - b.distance);

      for (const candidate of ranked) {
        if (candidate.distance > 60) continue;
        const tags = candidate.element.tags ?? {};
        const forward = inferOsmWayForward(candidate.element, trace);
        const raw = forward === true
          ? tags["turn:lanes:forward"] ?? tags["turn:lanes"]
          : forward === false
          ? tags["turn:lanes:backward"] ?? tags["turn:lanes"]
          : tags["turn:lanes"];
        if (!raw) continue;
        const guidance = parseOsmTurnLanes(raw, maneuverType);
        if (guidance) return guidance;
      }
      return null;
    } catch {
      // Try the next Overpass mirror.
    }
  }
  return null;
}

type MapboxLane = {
  valid?: boolean;
  active?: boolean;
  valid_indication?: string;
  indications?: string[];
};
type MapboxIntersection = {
  location?: number[];
  lanes?: MapboxLane[];
};
type MapboxStep = { intersections?: MapboxIntersection[] };
type MapboxLeg = { steps?: MapboxStep[] };
type MapboxRoute = { legs?: MapboxLeg[] };
type MapboxResponse = { code?: string; routes?: MapboxRoute[] };

function sampledTrace(trace: Coordinate[], maxPoints = 10): Coordinate[] {
  if (trace.length <= maxPoints) return trace;
  const result: Coordinate[] = [];
  for (let i = 0; i < maxPoints; i++) {
    const index = Math.round(i * (trace.length - 1) / (maxPoints - 1));
    result.push(trace[index]);
  }
  return result;
}

async function fetchMapboxGuidance(
  trace: Coordinate[],
  maneuverType: string | null | undefined,
): Promise<Guidance | null> {
  const token = Deno.env.get("MAPBOX_ACCESS_TOKEN")?.trim();
  if (!token) return null;

  const points = sampledTrace(trace);
  const coordinates = points.map((p) => `${p.longitude},${p.latitude}`).join(";");
  const radiuses = points.map(() => "25").join(";");
  const url = new URL(`https://api.mapbox.com/directions/v5/mapbox/driving/${coordinates}`);
  url.searchParams.set("steps", "true");
  url.searchParams.set("overview", "false");
  url.searchParams.set("continue_straight", "true");
  url.searchParams.set("radiuses", radiuses);
  url.searchParams.set("access_token", token);

  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(7500) });
    if (!response.ok) {
      console.error(JSON.stringify({
        event: "mapbox_lane_request_failed",
        status: response.status,
      }));
      return null;
    }
    const payload = await response.json() as MapboxResponse;
    if (payload.code !== "Ok") return null;

    const endpoint = trace[trace.length - 1];
    let best: { intersection: MapboxIntersection; distance: number } | null = null;

    for (const route of payload.routes ?? []) {
      for (const leg of route.legs ?? []) {
        for (const step of leg.steps ?? []) {
          for (const intersection of step.intersections ?? []) {
            if (!Array.isArray(intersection.lanes) || intersection.lanes.length === 0) continue;
            const location = intersection.location;
            if (!Array.isArray(location) || location.length < 2) continue;
            const distance = metersBetween(endpoint, {
              latitude: Number(location[1]),
              longitude: Number(location[0]),
            });
            if (distance <= 120 && (best == null || distance < best.distance)) {
              best = { intersection, distance };
            }
          }
        }
      }
    }

    if (!best) return null;
    const rawLanes = best.intersection.lanes ?? [];
    const hasActive = rawLanes.some((lane) => lane.active === true);
    const lanes = rawLanes.map((lane) => {
      const directions = (lane.indications ?? [])
        .map(directionFromText)
        .filter((direction) => direction !== "Unknown");
      const recommended = hasActive ? lane.active === true : lane.valid === true;
      return { directions, recommended };
    });

    return buildGuidance(lanes, maneuverType, "mapbox", "medium");
  } catch {
    return null;
  }
}

function equivalentGuidance(a: Guidance, b: Guidance): boolean {
  if (a.lanes.length !== b.lanes.length) return false;
  if (a.recommendedLaneIndexes.join(",") !== b.recommendedLaneIndexes.join(",")) return false;

  for (let i = 0; i < a.lanes.length; i++) {
    const ad = [...a.lanes[i].directions].sort().join(",");
    const bd = [...b.lanes[i].directions].sort().join(",");
    if (ad !== bd) return false;
  }
  return true;
}

function mergeGuidance(osm: Guidance | null, mapbox: Guidance | null): Guidance | null {
  if (osm && mapbox) {
    if (!equivalentGuidance(osm, mapbox)) {
      console.warn(JSON.stringify({
        event: "lane_guidance_conflict",
        osmLaneCount: osm.lanes.length,
        mapboxLaneCount: mapbox.lanes.length,
        osmRecommended: osm.recommendedLaneIndexes,
        mapboxRecommended: mapbox.recommendedLaneIndexes,
      }));
      return null;
    }
    return {
      ...mapbox,
      source: "osm+mapbox",
      confidence: "high",
    };
  }
  return mapbox ?? osm;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);
  if (!(await consumeNavigationRateLimit(req))) return json({ error: "rate_limited" }, 429);

  let body: LaneRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const trace = Array.isArray(body.trace) ? body.trace.filter(validCoordinate) : [];
  if (trace.length < 2) {
    return json({
      guidance: null,
      capability: "insufficient_trace",
      providersAttempted: [],
    });
  }

  const [osm, mapbox] = await Promise.all([
    fetchOsmGuidance(trace, body.maneuverType),
    fetchMapboxGuidance(trace, body.maneuverType),
  ]);

  const guidance = mergeGuidance(osm, mapbox);
  const mapboxConfigured = Boolean(Deno.env.get("MAPBOX_ACCESS_TOKEN")?.trim());

  return json({
    provider: guidance?.source ?? (mapboxConfigured ? "osm+mapbox" : "osm"),
    capability: guidance ? "available" : "no_trustworthy_lane_guidance",
    guidance,
    providersAttempted: mapboxConfigured ? ["osm", "mapbox"] : ["osm"],
    evidence: {
      osm: osm != null,
      mapbox: mapbox != null,
      agreement: osm != null && mapbox != null ? equivalentGuidance(osm, mapbox) : null,
    },
  });
});
