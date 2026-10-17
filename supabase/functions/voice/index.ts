import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type VoiceLanguage = "EnglishUs" | "EnglishUk" | "Spanish" | "French" | "NigerianPidgin";

type VoiceRequest = {
  text?: string;
  language?: VoiceLanguage;
};

const LANGUAGES = new Set<VoiceLanguage>([
  "EnglishUs",
  "EnglishUk",
  "Spanish",
  "French",
  "NigerianPidgin",
]);

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

async function rateLimit(req: Request): Promise<boolean> {
  try {
    const now = new Date();
    now.setSeconds(0, 0);
    const response = await adminFetch("/rest/v1/rpc/consume_navigation_rate_limit", {
      method: "POST",
      body: JSON.stringify({
        p_client_hash: await sha256Hex(clientAddress(req)),
        p_endpoint: "voice",
        p_bucket_start: now.toISOString(),
        p_limit: 90,
      }),
    });
    if (!response || !response.ok) return true;
    return (await response.json()) === true;
  } catch {
    return true;
  }
}

async function telemetry(
  language: string,
  outcome: "success" | "error" | "unconfigured",
  startedAt: number,
  audioBytes = 0,
) {
  try {
    await adminFetch("/rest/v1/voice_telemetry", {
      method: "POST",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({
        language,
        outcome,
        latency_ms: Math.round(performance.now() - startedAt),
        audio_bytes: audioBytes,
      }),
    });
  } catch {
    // Voice metrics never block navigation.
  }
}

function instructionsFor(language: VoiceLanguage): string {
  const localeHint = {
    EnglishUs: "American English",
    EnglishUk: "British English",
    Spanish: "natural Spanish",
    French: "natural French",
    NigerianPidgin: "natural Nigerian Pidgin with a Nigerian English accent",
  }[language];

  return [
    "Speak as a calm, confident turn-by-turn navigation voice.",
    "Be clear and concise, with brisk pacing and natural road-navigation intonation.",
    "Pronounce street names and place names carefully from the supplied text.",
    "Do not add, remove, explain, or paraphrase any words.",
    "Use " + localeHint + ".",
  ].join(" ");
}

Deno.serve(async (req: Request) => {
  const startedAt = performance.now();

  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);
  if (!(await rateLimit(req))) return json({ error: "rate_limited" }, 429);

  let body: VoiceRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const text = body.text?.trim() ?? "";
  const language = body.language;
  if (!language || !LANGUAGES.has(language) || text.length < 1 || text.length > 500) {
    void telemetry(language ?? "unknown", "error", startedAt);
    return json({ error: "invalid_request" }, 400);
  }

  const apiKey = Deno.env.get("OPENAI_API_KEY")?.trim();
  if (!apiKey) {
    void telemetry(language, "unconfigured", startedAt);
    return json({ error: "neural_voice_unconfigured" }, 503);
  }

  try {
    const response = await fetch("https://api.openai.com/v1/audio/speech", {
      method: "POST",
      headers: {
        "authorization": "Bearer " + apiKey,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: "gpt-4o-mini-tts",
        voice: "cedar",
        input: text,
        instructions: instructionsFor(language),
        response_format: "wav",
        speed: 1.04,
      }),
      signal: AbortSignal.timeout(5000),
    });

    if (!response.ok) {
      void telemetry(language, "error", startedAt);
      return json({ error: "neural_voice_unavailable" }, 503);
    }

    const audio = new Uint8Array(await response.arrayBuffer());
    if (audio.byteLength === 0 || audio.byteLength > 2_000_000) {
      void telemetry(language, "error", startedAt);
      return json({ error: "invalid_audio" }, 503);
    }

    void telemetry(language, "success", startedAt, audio.byteLength);
    return new Response(audio, {
      status: 200,
      headers: {
        "content-type": "audio/wav",
        "cache-control": "private, max-age=86400",
        "x-openmaps-voice-provider": "openai",
        "x-openmaps-voice-model": "gpt-4o-mini-tts",
      },
    });
  } catch {
    void telemetry(language, "error", startedAt);
    return json({ error: "neural_voice_unavailable" }, 503);
  }
});
