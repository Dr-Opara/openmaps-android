import "jsr:@supabase/functions-js/edge-runtime.d.ts";

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

async function consumeRateLimit(req: Request): Promise<boolean> {
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
        p_endpoint: "routing-fallback",
        p_bucket_start: now.toISOString(),
        p_limit: 30,
      }),
    });
    return !response.ok || (await response.json()) === true;
  } catch {
    return true;
  }
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);
  if (!(await consumeRateLimit(req))) return json({ error: "rate_limited" }, 429);

  const base = supabaseUrl();
  const key = serviceRoleKey();
  if (!base || !key) return json({ error: "telemetry_unavailable" }, 503);

  try {
    const response = await fetch(base + "/rest/v1/routing_telemetry", {
      method: "POST",
      headers: {
        apikey: key,
        authorization: "Bearer " + key,
        "content-type": "application/json",
        prefer: "return=minimal",
      },
      body: JSON.stringify({
        outcome: "fallback_success",
        latency_ms: 0,
        alternatives_count: 0,
        traffic_aware: false,
        provider: "valhalla",
        fallback_used: true,
      }),
    });
    if (!response.ok) return json({ error: "telemetry_write_failed" }, 503);
    return json({ ok: true });
  } catch {
    return json({ error: "telemetry_write_failed" }, 503);
  }
});
