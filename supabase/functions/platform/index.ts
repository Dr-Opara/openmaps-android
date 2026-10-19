import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const API_VERSION = "v1";

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

function requestId(req: Request): string {
  const supplied = req.headers.get("x-openmaps-request-id")?.trim();
  if (supplied && /^[A-Za-z0-9._:-]{8,128}$/.test(supplied)) return supplied;
  return crypto.randomUUID();
}

function json(body: unknown, status: number, id: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      "x-openmaps-api-version": API_VERSION,
      "x-openmaps-request-id": id,
    },
  });
}

Deno.serve((req: Request) => {
  const id = requestId(req);

  if (req.method !== "GET") {
    return json({ error: "method_not_allowed", requestId: id }, 405, id);
  }
  if (!authorized(req)) {
    return json({ error: "unauthorized", requestId: id }, 401, id);
  }

  return json(
    {
      service: "openmaps-backend",
      status: "ok",
      apiVersion: API_VERSION,
      requestId: id,
      capabilities: {
        search: true,
        reverseGeocoding: true,
        routing: true,
        trafficIncidents: true,
        laneGuidance: true,
        places: true,
        tripSharing: true,
        communityReports: true,
        neuralVoice: true,
      },
      providerReadiness: {
        commercialNavigation: Boolean(Deno.env.get("HERE_API_KEY")?.trim()),
        laneEnrichment: Boolean(Deno.env.get("MAPBOX_ACCESS_TOKEN")?.trim()),
        neuralVoice: Boolean(Deno.env.get("OPENAI_API_KEY")?.trim()),
      },
    },
    200,
    id,
  );
});
