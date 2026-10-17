import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };

type CreateRequest = {
  action: "create";
  destination?: { name?: string; latitude?: number; longitude?: number };
  currentLocation?: Coordinate & { headingDegrees?: number | null; speedMetersPerSecond?: number | null };
  expiresAtMillis?: number;
  remainingDistanceMeters?: number | null;
  remainingDurationSeconds?: number | null;
};

type UpdateRequest = {
  action: "update";
  sessionId?: string;
  ownerToken?: string;
  currentLocation?: Coordinate & { headingDegrees?: number | null; speedMetersPerSecond?: number | null };
  remainingDistanceMeters?: number | null;
  remainingDurationSeconds?: number | null;
  arrived?: boolean;
};

type StopRequest = {
  action: "stop";
  sessionId?: string;
  ownerToken?: string;
};

type TripShareRequest = CreateRequest | UpdateRequest | StopRequest;

const MAX_EXPIRY_MS = 24 * 60 * 60 * 1000;
const MIN_EXPIRY_MS = 5 * 60 * 1000;

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

function html(body: string, status = 200) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      "content-security-policy": "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
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

function randomToken(bytes = 32): string {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...data))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function clientAddress(req: Request): string {
  return req.headers.get("cf-connecting-ip")?.trim()
    || req.headers.get("x-forwarded-for")?.split(",")[0]?.trim()
    || "unknown";
}

async function rateLimit(req: Request, endpoint: string, limit: number): Promise<boolean> {
  try {
    const hash = await sha256Hex(clientAddress(req));
    const now = new Date();
    now.setSeconds(0, 0);
    const response = await adminFetch("/rest/v1/rpc/consume_navigation_rate_limit", {
      method: "POST",
      body: JSON.stringify({
        p_client_hash: hash,
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


async function writeTelemetry(event: string, outcome: "success" | "error", startedAt: number): Promise<void> {
  try {
    await adminFetch("/rest/v1/trip_share_telemetry", {
      method: "POST",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({
        event,
        outcome,
        latency_ms: Math.round(performance.now() - startedAt),
      }),
    });
  } catch {
    // Observability must never block navigation or sharing.
  }
}

function validCoordinate(value: Coordinate | undefined): value is Coordinate {
  return !!value &&
    Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function finiteOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

async function createSession(body: CreateRequest) {
  if (!body.destination || !validCoordinate(body.currentLocation)) return json({ error: "invalid_request" }, 400);

  const destinationLatitude = Number(body.destination.latitude);
  const destinationLongitude = Number(body.destination.longitude);
  if (!Number.isFinite(destinationLatitude) || !Number.isFinite(destinationLongitude)) {
    return json({ error: "invalid_destination" }, 400);
  }

  const now = Date.now();
  const requestedExpiry = Number(body.expiresAtMillis);
  const expiresAtMillis = Math.min(
    now + MAX_EXPIRY_MS,
    Math.max(now + MIN_EXPIRY_MS, Number.isFinite(requestedExpiry) ? requestedExpiry : now + MIN_EXPIRY_MS),
  );

  const ownerToken = randomToken();
  const shareToken = randomToken();
  const response = await adminFetch("/rest/v1/trip_share_sessions", {
    method: "POST",
    headers: { prefer: "return=representation" },
    body: JSON.stringify({
      owner_token_hash: await sha256Hex(ownerToken),
      share_token_hash: await sha256Hex(shareToken),
      destination_name: String(body.destination.name ?? "Destination").slice(0, 200),
      destination_latitude: destinationLatitude,
      destination_longitude: destinationLongitude,
      current_latitude: body.currentLocation.latitude,
      current_longitude: body.currentLocation.longitude,
      heading_degrees: finiteOrNull(body.currentLocation.headingDegrees),
      speed_meters_per_second: finiteOrNull(body.currentLocation.speedMetersPerSecond),
      remaining_distance_meters: finiteOrNull(body.remainingDistanceMeters),
      remaining_duration_seconds: finiteOrNull(body.remainingDurationSeconds),
      expires_at: new Date(expiresAtMillis).toISOString(),
    }),
  });

  if (!response || !response.ok) return json({ error: "storage_unavailable" }, 503);
  const rows = await response.json();
  const sessionId = Array.isArray(rows) ? rows[0]?.id : null;
  if (typeof sessionId !== "string") return json({ error: "storage_unavailable" }, 503);

  const base = supabaseUrl();
  if (!base) return json({ error: "service_not_configured" }, 503);

  return json({
    sessionId,
    ownerToken,
    url: base + "/functions/v1/trip-share?token=" + encodeURIComponent(shareToken),
    expiresAtMillis,
  });
}

async function ownerSession(sessionId: string, ownerToken: string) {
  const response = await adminFetch(
    "/rest/v1/trip_share_sessions?id=eq." + encodeURIComponent(sessionId) +
      "&owner_token_hash=eq." + encodeURIComponent(await sha256Hex(ownerToken)) +
      "&select=id,status,expires_at&limit=1",
    { method: "GET" },
  );
  if (!response || !response.ok) return null;
  const rows = await response.json();
  return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
}

async function updateSession(body: UpdateRequest) {
  if (!body.sessionId || !body.ownerToken || !validCoordinate(body.currentLocation)) {
    return json({ error: "invalid_request" }, 400);
  }
  const session = await ownerSession(body.sessionId, body.ownerToken);
  if (!session) return json({ error: "not_found" }, 404);
  if (session.status !== "active") return json({ error: "session_closed" }, 409);
  if (new Date(String(session.expires_at)).getTime() <= Date.now()) return json({ error: "expired" }, 410);

  const arrived = body.arrived === true;
  const patch: Record<string, unknown> = {
    current_latitude: body.currentLocation.latitude,
    current_longitude: body.currentLocation.longitude,
    heading_degrees: finiteOrNull(body.currentLocation.headingDegrees),
    speed_meters_per_second: finiteOrNull(body.currentLocation.speedMetersPerSecond),
    remaining_distance_meters: arrived ? 0 : finiteOrNull(body.remainingDistanceMeters),
    remaining_duration_seconds: arrived ? 0 : finiteOrNull(body.remainingDurationSeconds),
    updated_at: new Date().toISOString(),
  };
  if (arrived) {
    patch.status = "arrived";
    patch.arrived_at = new Date().toISOString();
  }

  const response = await adminFetch(
    "/rest/v1/trip_share_sessions?id=eq." + encodeURIComponent(body.sessionId),
    {
      method: "PATCH",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify(patch),
    },
  );
  if (!response || !response.ok) return json({ error: "storage_unavailable" }, 503);
  return json({ ok: true, status: arrived ? "arrived" : "active" });
}

async function stopSession(body: StopRequest) {
  if (!body.sessionId || !body.ownerToken) return json({ error: "invalid_request" }, 400);
  const session = await ownerSession(body.sessionId, body.ownerToken);
  if (!session) return json({ error: "not_found" }, 404);

  const response = await adminFetch(
    "/rest/v1/trip_share_sessions?id=eq." + encodeURIComponent(body.sessionId),
    {
      method: "PATCH",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({
        status: "stopped",
        stopped_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      }),
    },
  );
  if (!response || !response.ok) return json({ error: "storage_unavailable" }, 503);
  return json({ ok: true });
}

async function publicSession(token: string) {
  const response = await adminFetch(
    "/rest/v1/trip_share_sessions?share_token_hash=eq." + encodeURIComponent(await sha256Hex(token)) +
      "&select=destination_name,destination_latitude,destination_longitude,current_latitude,current_longitude,heading_degrees,speed_meters_per_second,remaining_distance_meters,remaining_duration_seconds,status,updated_at,expires_at,arrived_at&limit=1",
    { method: "GET" },
  );
  if (!response || !response.ok) return null;
  const rows = await response.json();
  return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
}

function safePublicPayload(row: Record<string, unknown>) {
  const expired = new Date(String(row.expires_at)).getTime() <= Date.now();
  return {
    destination: {
      name: row.destination_name,
      latitude: row.destination_latitude,
      longitude: row.destination_longitude,
    },
    location: row.status === "arrived" ? null : {
      latitude: row.current_latitude,
      longitude: row.current_longitude,
      headingDegrees: row.heading_degrees,
      speedMetersPerSecond: row.speed_meters_per_second,
    },
    remainingDistanceMeters: row.remaining_distance_meters,
    remainingDurationSeconds: row.remaining_duration_seconds,
    status: expired && row.status === "active" ? "expired" : row.status,
    updatedAt: row.updated_at,
    expiresAt: row.expires_at,
    arrivedAt: row.arrived_at,
  };
}

function viewerPage(): string {
  return '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>OpenMaps Live Trip</title><style>:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}body{margin:0;background:#07111f;color:#f7fbff;min-height:100vh;display:grid;place-items:center}main{width:min(92vw,620px);background:#0d1b2a;border:1px solid #21344a;border-radius:24px;padding:24px;box-shadow:0 24px 80px #0008}.brand{font-weight:800;letter-spacing:.08em;color:#6ee7ff}.muted{color:#9db0c4}.big{font-size:2rem;font-weight:800;margin:.25rem 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:18px}.card{background:#12243a;border-radius:16px;padding:16px}.label{font-size:.75rem;text-transform:uppercase;letter-spacing:.08em;color:#8da3ba}.value{font-size:1.1rem;font-weight:700;margin-top:6px}.status{display:inline-block;padding:7px 10px;border-radius:999px;background:#173c57;color:#8ee8ff;font-weight:700}footer{margin-top:18px;font-size:.78rem;color:#71869c}</style></head>' +
    '<body><main><div class="brand">OPENMAPS</div><div class="muted">Live trip</div><div class="big" id="destination">Loading trip...</div><div class="status" id="status">Connecting</div><div class="grid">' +
    '<div class="card"><div class="label">ETA</div><div class="value" id="eta">-</div></div><div class="card"><div class="label">Remaining</div><div class="value" id="distance">-</div></div>' +
    '<div class="card"><div class="label">Current location</div><div class="value" id="location">-</div></div><div class="card"><div class="label">Last updated</div><div class="value" id="updated">-</div></div></div>' +
    '<footer>Location sharing automatically ends when the trip arrives, is stopped, or expires.</footer>' +
    '<script>function fd(m){if(m==null)return "-";var mi=m/1609.344;return mi<0.2?Math.round(m*3.28084)+" ft":mi.toFixed(mi<10?1:0)+" mi"}function ft(s){if(s==null)return "-";var m=Math.max(0,Math.round(s/60));return m<60?m+" min":Math.floor(m/60)+" hr "+(m%60)+" min"}async function r(){try{var u=new URL(location.href);u.searchParams.set("format","json");var x=await fetch(u.toString(),{cache:"no-store"});var d=await x.json();if(!x.ok){document.getElementById("status").textContent=d.error||"Unavailable";return}document.getElementById("destination").textContent=d.destination&&d.destination.name||"Destination";document.getElementById("status").textContent=String(d.status||"active").replace(/^./,function(c){return c.toUpperCase()});document.getElementById("eta").textContent=d.status==="arrived"?"Arrived":ft(d.remainingDurationSeconds);document.getElementById("distance").textContent=fd(d.remainingDistanceMeters);document.getElementById("location").textContent=d.location?Number(d.location.latitude).toFixed(5)+", "+Number(d.location.longitude).toFixed(5):"Sharing ended";document.getElementById("updated").textContent=new Date(d.updatedAt).toLocaleTimeString();if(["arrived","stopped","expired"].indexOf(d.status)>=0)return;setTimeout(r,3000)}catch(e){document.getElementById("status").textContent="Reconnecting";setTimeout(r,5000)}}r();</script></main></body></html>';
}

Deno.serve(async (req: Request) => {
  const startedAt = performance.now();
  const url = new URL(req.url);

  if (req.method === "GET") {
    if (!(await rateLimit(req, "trip-share-view", 120))) return json({ error: "rate_limited" }, 429);
    const token = url.searchParams.get("token")?.trim();
    const wantsJson = url.searchParams.get("format") === "json";
    if (!token || token.length < 20) return wantsJson ? json({ error: "not_found" }, 404) : html("<p>Trip link unavailable.</p>", 404);
    const row = await publicSession(token);
    if (!row) {
      void writeTelemetry("error", "error", startedAt);
      return wantsJson ? json({ error: "not_found" }, 404) : html("<p>Trip link unavailable.</p>", 404);
    }
    const payload = safePublicPayload(row);
    if (payload.status === "stopped" || payload.status === "expired") {
      void writeTelemetry("view", "success", startedAt);
      return wantsJson
        ? json({ status: payload.status, error: "sharing_ended" }, 410)
        : html("<!doctype html><title>OpenMaps</title><p>This trip is no longer being shared.</p>", 410);
    }
    void writeTelemetry("view", "success", startedAt);
    return wantsJson ? json(payload) : html(viewerPage());
  }

  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

  let body: TripShareRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (body.action === "create") {
    if (!(await rateLimit(req, "trip-share-create", 20))) return json({ error: "rate_limited" }, 429);
    const response = await createSession(body);
    void writeTelemetry("create", response.ok ? "success" : "error", startedAt);
    return response;
  }
  if (body.action === "update") {
    if (!(await rateLimit(req, "trip-share-update", 90))) return json({ error: "rate_limited" }, 429);
    const response = await updateSession(body);
    void writeTelemetry(body.arrived === true ? "arrive" : "update", response.ok ? "success" : "error", startedAt);
    return response;
  }
  if (body.action === "stop") {
    if (!(await rateLimit(req, "trip-share-stop", 30))) return json({ error: "rate_limited" }, 429);
    const response = await stopSession(body);
    void writeTelemetry("stop", response.ok ? "success" : "error", startedAt);
    return response;
  }
  return json({ error: "invalid_action" }, 400);
});
