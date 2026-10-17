import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type ReportType =
  | "Police" | "Traffic" | "Crash" | "LaneClosed" | "Construction"
  | "RoadHazard" | "Flooding" | "DisabledVehicle" | "ObjectOnRoad";

type RequestBody =
  | { action: "submit"; type?: ReportType; location?: Coordinate }
  | { action: "query"; routeGeometry?: Coordinate[]; corridorMeters?: number }
  | { action: "vote"; reportId?: string; vote?: "confirm" | "dismiss" };

const REPORT_TYPES = new Set<ReportType>([
  "Police","Traffic","Crash","LaneClosed","Construction",
  "RoadHazard","Flooding","DisabledVehicle","ObjectOnRoad",
]);

const TTL_MINUTES: Record<ReportType, number> = {
  Police: 30,
  Traffic: 20,
  Crash: 90,
  LaneClosed: 120,
  Construction: 360,
  RoadHazard: 90,
  Flooding: 180,
  DisabledVehicle: 60,
  ObjectOnRoad: 45,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
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

function supabaseUrl(): string | null {
  return Deno.env.get("SUPABASE_URL")?.trim() || null;
}

function serviceRoleKey(): string | null {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim() || null;
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

async function clientHash(req: Request): Promise<string> {
  return sha256Hex(clientAddress(req));
}

async function consumeRateLimit(req: Request, endpoint: string, limit: number): Promise<boolean> {
  try {
    const now = new Date();
    now.setSeconds(0, 0);
    const response = await adminFetch("/rest/v1/rpc/consume_navigation_rate_limit", {
      method: "POST",
      body: JSON.stringify({
        p_client_hash: await clientHash(req),
        p_endpoint: endpoint,
        p_bucket_start: now.toISOString(),
        p_limit: limit,
      }),
    });
    if (!response || !response.ok) return true;
    return (await response.json()) === true;
  } catch {
    return true;
  }
}

async function telemetry(event: string, outcome: "success" | "error", startedAt: number): Promise<void> {
  try {
    await adminFetch("/rest/v1/community_report_telemetry", {
      method: "POST",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({
        event,
        outcome,
        latency_ms: Math.round(performance.now() - startedAt),
      }),
    });
  } catch {
    // Metrics never block navigation.
  }
}

function validCoordinate(value: Coordinate | undefined): value is Coordinate {
  return !!value &&
    Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function haversineMeters(a: Coordinate, b: Coordinate): number {
  const r = 6371000;
  const dLat = (b.latitude - a.latitude) * Math.PI / 180;
  const dLon = (b.longitude - a.longitude) * Math.PI / 180;
  const lat1 = a.latitude * Math.PI / 180;
  const lat2 = b.latitude * Math.PI / 180;
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLon / 2);
  const h = s1 * s1 + Math.cos(lat1) * Math.cos(lat2) * s2 * s2;
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
}

function boundingBox(points: Coordinate[], paddingMeters: number) {
  let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
  for (const p of points) {
    minLat = Math.min(minLat, p.latitude);
    maxLat = Math.max(maxLat, p.latitude);
    minLon = Math.min(minLon, p.longitude);
    maxLon = Math.max(maxLon, p.longitude);
  }
  const midLat = (minLat + maxLat) / 2;
  const latPad = paddingMeters / 111320;
  const lonScale = Math.max(0.2, Math.cos(midLat * Math.PI / 180));
  const lonPad = paddingMeters / (111320 * lonScale);
  return {
    minLat: minLat - latPad,
    maxLat: maxLat + latPad,
    minLon: minLon - lonPad,
    maxLon: maxLon + lonPad,
  };
}

async function findCluster(type: ReportType, location: Coordinate) {
  const box = boundingBox([location], 120);
  const path = "/rest/v1/community_reports?status=eq.active" +
    "&report_type=eq." + encodeURIComponent(type) +
    "&expires_at=gt." + encodeURIComponent(new Date().toISOString()) +
    "&latitude=gte." + box.minLat +
    "&latitude=lte." + box.maxLat +
    "&longitude=gte." + box.minLon +
    "&longitude=lte." + box.maxLon +
    "&select=id,latitude,longitude,confidence,confirmations,dismissals&limit=20";
  const response = await adminFetch(path, { method: "GET" });
  if (!response || !response.ok) return null;
  const rows = await response.json() as Array<Record<string, unknown>>;
  let best: Record<string, unknown> | null = null;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const row of rows) {
    const candidate = { latitude: Number(row.latitude), longitude: Number(row.longitude) };
    const distance = haversineMeters(location, candidate);
    if (distance <= 100 && distance < bestDistance) {
      bestDistance = distance;
      best = row;
    }
  }
  return best;
}

async function castVote(reportId: string, voterHash: string, vote: "confirm" | "dismiss") {
  const response = await adminFetch("/rest/v1/community_report_votes?on_conflict=report_id,voter_hash", {
    method: "POST",
    headers: { prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({ report_id: reportId, voter_hash: voterHash, vote }),
  });
  if (!response || !response.ok) return false;

  const recompute = await adminFetch("/rest/v1/rpc/recompute_community_report_confidence", {
    method: "POST",
    body: JSON.stringify({ p_report_id: reportId }),
  });
  return !!recompute?.ok;
}

async function submit(req: Request, type: ReportType, location: Coordinate) {
  const hash = await clientHash(req);
  const cluster = await findCluster(type, location);

  if (cluster && typeof cluster.id === "string") {
    await castVote(cluster.id, hash, "confirm");
    return { response: json({ reportId: cluster.id, clustered: true, status: "active" }), clustered: true };
  }

  const expiresAt = new Date(Date.now() + TTL_MINUTES[type] * 60_000).toISOString();
  const response = await adminFetch("/rest/v1/community_reports", {
    method: "POST",
    headers: { prefer: "return=representation" },
    body: JSON.stringify({
      report_type: type,
      latitude: location.latitude,
      longitude: location.longitude,
      submitter_hash: hash,
      status: "active",
      confidence: 0.35,
      expires_at: expiresAt,
    }),
  });
  if (!response || !response.ok) return { response: json({ error: "storage_unavailable" }, 503), clustered: false };
  const rows = await response.json();
  const id = Array.isArray(rows) ? rows[0]?.id : null;
  if (typeof id !== "string") return { response: json({ error: "storage_unavailable" }, 503), clustered: false };
  return { response: json({ reportId: id, clustered: false, status: "active", expiresAt }), clustered: false };
}

function publicReport(row: Record<string, unknown>) {
  return {
    id: row.id,
    type: row.report_type,
    latitude: row.latitude,
    longitude: row.longitude,
    confidence: row.confidence,
    confirmations: row.confirmations,
    dismissals: row.dismissals,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
  };
}

async function query(routeGeometry: Coordinate[], corridorMeters: number) {
  if (routeGeometry.length < 2 || routeGeometry.length > 2000 || routeGeometry.some((p) => !validCoordinate(p))) {
    return json({ error: "invalid_route_geometry" }, 400);
  }
  const corridor = Math.min(1000, Math.max(25, corridorMeters));
  const box = boundingBox(routeGeometry, corridor);
  const path = "/rest/v1/community_reports?status=eq.active" +
    "&expires_at=gt." + encodeURIComponent(new Date().toISOString()) +
    "&confidence=gte.0.20" +
    "&latitude=gte." + box.minLat +
    "&latitude=lte." + box.maxLat +
    "&longitude=gte." + box.minLon +
    "&longitude=lte." + box.maxLon +
    "&select=id,report_type,latitude,longitude,confidence,confirmations,dismissals,created_at,expires_at" +
    "&order=confidence.desc&limit=100";
  const response = await adminFetch(path, { method: "GET" });
  if (!response || !response.ok) return json({ error: "storage_unavailable" }, 503);
  const rows = await response.json() as Array<Record<string, unknown>>;
  return json({ reports: rows.map(publicReport) });
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

  if (body.action === "submit") {
    if (!(await consumeRateLimit(req, "community-report-submit", 12))) return json({ error: "rate_limited" }, 429);
    if (!body.type || !REPORT_TYPES.has(body.type) || !validCoordinate(body.location)) {
      void telemetry("error", "error", startedAt);
      return json({ error: "invalid_report" }, 400);
    }
    const result = await submit(req, body.type, body.location);
    void telemetry(result.clustered ? "cluster" : "submit", result.response.ok ? "success" : "error", startedAt);
    return result.response;
  }

  if (body.action === "query") {
    if (!(await consumeRateLimit(req, "community-report-query", 60))) return json({ error: "rate_limited" }, 429);
    const response = await query(body.routeGeometry ?? [], Number(body.corridorMeters ?? 250));
    void telemetry("query", response.ok ? "success" : "error", startedAt);
    return response;
  }

  if (body.action === "vote") {
    if (!(await consumeRateLimit(req, "community-report-vote", 30))) return json({ error: "rate_limited" }, 429);
    if (!body.reportId || (body.vote !== "confirm" && body.vote !== "dismiss")) {
      void telemetry("error", "error", startedAt);
      return json({ error: "invalid_vote" }, 400);
    }
    const existing = await adminFetch(
      "/rest/v1/community_reports?id=eq." + encodeURIComponent(body.reportId) +
      "&status=eq.active&expires_at=gt." + encodeURIComponent(new Date().toISOString()) +
      "&select=id&limit=1",
      { method: "GET" },
    );
    if (!existing || !existing.ok) return json({ error: "storage_unavailable" }, 503);
    const rows = await existing.json();
    if (!Array.isArray(rows) || rows.length === 0) return json({ error: "report_unavailable" }, 404);

    const ok = await castVote(body.reportId, await clientHash(req), body.vote);
    void telemetry(body.vote === "confirm" ? "confirm" : "dismiss", ok ? "success" : "error", startedAt);
    return ok ? json({ ok: true }) : json({ error: "storage_unavailable" }, 503);
  }

  return json({ error: "invalid_action" }, 400);
});
