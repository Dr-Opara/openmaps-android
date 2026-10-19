import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type VoiceLanguage =
  | "EnglishUs"
  | "EnglishUk"
  | "Spanish"
  | "French"
  | "Arabic"
  | "Hebrew"
  | "NigerianPidgin";

type VoiceRequest = {
  text?: string;
  language?: VoiceLanguage;
};

const LANGUAGES = new Set<VoiceLanguage>([
  "EnglishUs",
  "EnglishUk",
  "Spanish",
  "French",
  "Arabic",
  "Hebrew",
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
        p_limit: 30,
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

function validNavigationPhrase(text: string, language: VoiceLanguage): boolean {
  if (text.length < 1 || text.length > 240) return false;
  if (/[\r\n\t<>]/.test(text) || /https?:\/\//i.test(text)) return false;

  const allowedPrefixes: Record<VoiceLanguage, string[]> = {
    EnglishUs: [
      "Navigation started.", "Rerouting.", "Route updated.", "Approaching your destination.",
      "You have arrived.", "In half a mile, ", "In 500 feet, ", "In 800 meters, ",
      "In 150 meters, ", "In 200 yards, ", "Continue", "Keep ", "Turn ", "Make ", "Merge",
      "Take ", "Enter ",
    ],
    EnglishUk: [
      "Navigation started.", "Rerouting.", "Route updated.", "Approaching your destination.",
      "You have arrived.", "In half a mile, ", "In 500 feet, ", "In 800 meters, ",
      "In 150 meters, ", "In 200 yards, ", "Continue", "Keep ", "Turn ", "Make ", "Merge",
      "Take ", "Enter ",
    ],
    Spanish: [
      "Navegación iniciada.", "Recalculando ruta.", "Ruta actualizada.", "Se acerca a su destino.",
      "Ha llegado a su destino.", "En media milla, ", "En 500 pies, ", "En 800 metros, ",
      "En 150 metros, ", "En 200 yardas, ", "Continúe", "Manténgase", "Gire ", "Haga ",
      "Incorpórese", "Tome ", "Entre ",
    ],
    French: [
      "Navigation commencée.", "Recalcul de l'itinéraire.", "Recalcul de l’itinéraire.",
      "Itinéraire mis à jour.",
      "Vous approchez de votre destination.", "Vous êtes arrivé à destination.",
      "Dans un demi-mile, ", "Dans 500 pieds, ", "Dans 800 mètres, ", "Dans 150 mètres, ",
      "Dans 200 yards, ", "Continuez", "Restez ", "Tournez ", "Faites ", "Rejoignez ",
      "Prenez ", "Entrez ",
    ],
    Arabic: [
      "بدأت الملاحة.", "جارٍ إعادة التوجيه.", "تم تحديث المسار.", "أنت تقترب من وجهتك.",
      "لقد وصلت إلى وجهتك.", "بعد 800 متر، ", "بعد 150 متر، ", "بعد نصف ميل، ",
      "بعد 200 ياردة، ", "بعد 500 قدم، ", "استمر", "الزم ", "انعطف ", "قم ",
      "اندمج ", "اسلك ", "ادخل ",
    ],
    Hebrew: [
      "הניווט התחיל.", "מחשב מסלול מחדש.", "המסלול עודכן.", "אתם מתקרבים ליעד.",
      "הגעתם ליעד.", "בעוד 800 מטר, ", "בעוד 150 מטר, ", "בעוד חצי מייל, ",
      "בעוד 200 יארד, ", "בעוד 500 רגל, ", "המשיכו", "היצמדו ", "פנו ", "בצעו ",
      "השתלבו ", "צאו ", "היכנסו ",
    ],
    NigerianPidgin: [
      "Navigation don start.", "I dey find new road.", "New road don ready.",
      "You dey near your destination.", "You don reach your destination.", "For about half mile, ",
      "For about 500 feet, ", "For about 800 meters, ", "For about 150 meters, ",
      "For about 200 yards, ", "Continue", "Stay ", "Turn ", "Make ", "Merge ", "Take ", "Enter ",
    ],
  };

  return allowedPrefixes[language].some((prefix) => text.startsWith(prefix));
}

function instructionsFor(language: VoiceLanguage): string {
  const localeHint = {
    EnglishUs: "American English",
    EnglishUk: "British English",
    Spanish: "natural Spanish",
    French: "natural French",
    Arabic: "natural Modern Standard Arabic",
    Hebrew: "natural Hebrew",
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
  if (!language || !LANGUAGES.has(language) || !validNavigationPhrase(text, language)) {
    void telemetry(language ?? "unknown", "error", startedAt);
    return json({ error: "invalid_request" }, 400);
  }

  const apiKey = Deno.env.get("OPENAI_API_KEY")?.trim();
  if (!apiKey) {
    void telemetry(language, "unconfigured", startedAt);
    return json({ error: "neural_voice_unconfigured" }, 503);
  }

  try {
    const speechBody = JSON.stringify({
      model: "gpt-4o-mini-tts",
      voice: "cedar",
      input: text,
      instructions: instructionsFor(language),
      response_format: "wav",
      speed: 1.04,
    });

    let response: Response | null = null;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      response = await fetch("https://api.openai.com/v1/audio/speech", {
        method: "POST",
        headers: {
          "authorization": "Bearer " + apiKey,
          "content-type": "application/json",
        },
        body: speechBody,
        // Multilingual neural speech can legitimately exceed 5s; keep a bounded timeout without
        // turning normal provider latency into a false service outage.
        signal: AbortSignal.timeout(12000),
      });

      if (response.ok) break;
      const retryable = response.status === 429 || response.status >= 500;
      if (!retryable || attempt === 1) {
        console.warn("voice_provider_error", { language, status: response.status, attempt: attempt + 1 });
        void telemetry(language, "error", startedAt);
        return json({ error: "neural_voice_unavailable" }, 503);
      }

      console.warn("voice_provider_retry", { language, status: response.status, attempt: attempt + 1 });
      await new Promise((resolve) => setTimeout(resolve, 250));
    }

    if (!response?.ok) {
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
