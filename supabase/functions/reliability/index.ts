import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const EVENTS = new Set([
  "app_launch","previous_crash","route_request","route_ready","route_failed",
  "navigation_started","navigation_start_failed","off_route_confirmed",
  "reroute_triggered","reroute_succeeded","reroute_failed","arrived",
  "navigation_ended","ar_availability","ar_ready","ar_fallback","camera_failure",
]);
const OUTCOMES = new Set(["info","success","error","degraded"]);
const SENSITIVE_METADATA_FRAGMENTS = [
  "lat","lon","location","address","destination","query","email","phone","name",
];
const REQUESTS_PER_MINUTE = 120;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
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

async function clientIdentityHash(req: Request): Promise<string> {
  const installId = req.headers.get("x-openmaps-client-id")?.trim();
  if (installId && /^[A-Za-z0-9._:-]{8,128}$/.test(installId)) {
    return sha256Hex("install|" + installId);
  }
  return sha256Hex("network|" + clientAddress(req));
}

async function consumeRateLimit(req: Request, clientHash: string): Promise<boolean> {
  try {
    const now = new Date();
    now.setSeconds(0, 0);
    const response = await adminFetch("/rest/v1/rpc/consume_navigation_rate_limit", {
      method: "POST",
      body: JSON.stringify({
        p_client_hash: clientHash,
        p_endpoint: "reliability",
        p_bucket_start: now.toISOString(),
        p_limit: REQUESTS_PER_MINUTE,
      }),
    });
    if (!response || !response.ok) return true;
    return (await response.json()) === true;
  } catch {
    return true;
  }
}

function safeMetadata(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const output: Record<string, string> = {};
  for (const [rawKey, rawValue] of Object.entries(value as Record<string, unknown>).slice(0, 12)) {
    const key = rawKey.trim().slice(0, 64);
    const normalized = key.toLowerCase();
    if (!key || SENSITIVE_METADATA_FRAGMENTS.some((fragment) => normalized.includes(fragment))) continue;
    if (typeof rawValue !== "string") continue;
    output[key] = rawValue.slice(0, 1500);
  }
  return output;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const event = typeof body.event === "string" ? body.event : "";
  const outcome = typeof body.outcome === "string" ? body.outcome : "";
  const appVersion = typeof body.appVersion === "string" ? body.appVersion.trim().slice(0, 64) : "";
  const appBuild = Number(body.appBuild);
  const deviceApi = Number(body.deviceApi);
  const reason = typeof body.reason === "string" && body.reason.trim()
    ? body.reason.trim().slice(0, 120)
    : null;
  const durationMs = body.durationMs == null ? null : Number(body.durationMs);

  if (!EVENTS.has(event) || !OUTCOMES.has(outcome) || !appVersion ||
      !Number.isInteger(appBuild) || appBuild < 0 ||
      !Number.isInteger(deviceApi) || deviceApi < 1 ||
      (durationMs != null && (!Number.isInteger(durationMs) || durationMs < 0))) {
    return json({ error: "invalid_request" }, 400);
  }

  const clientHash = await clientIdentityHash(req);
  if (!(await consumeRateLimit(req, clientHash))) return json({ error: "rate_limited" }, 429);

  const response = await adminFetch("/rest/v1/mobile_reliability_events", {
    method: "POST",
    headers: { prefer: "return=minimal" },
    body: JSON.stringify({
      client_hash: clientHash,
      event,
      outcome,
      reason,
      app_version: appVersion,
      app_build: appBuild,
      device_api: deviceApi,
      duration_ms: durationMs,
      metadata: safeMetadata(body.metadata),
    }),
  });

  if (!response || !response.ok) return json({ error: "storage_unavailable" }, 503);
  return json({ accepted: true }, 202);
});
