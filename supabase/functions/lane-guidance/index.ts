import "jsr:@supabase/functions-js/edge-runtime.d.ts";

type Coordinate = { latitude: number; longitude: number };
type LaneRequest = {
  trace?: Coordinate[];
  maneuverType?: string | null;
};

type LaneRow = {
  linkId: string;
  laneNumber: number;
  directionText: string;
  travelDirection: string | null;
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

function validCoordinate(value: Coordinate): boolean {
  return Number.isFinite(value.latitude) && value.latitude >= -90 && value.latitude <= 90 &&
    Number.isFinite(value.longitude) && value.longitude >= -180 && value.longitude <= 180;
}

function collectLaneRows(value: unknown, output: LaneRow[] = []): LaneRow[] {
  if (Array.isArray(value)) {
    value.forEach((item) => collectLaneRows(item, output));
    return output;
  }
  if (!value || typeof value !== "object") return output;

  const record = value as Record<string, unknown>;
  const laneNumberRaw = record.LANE_NUMBER ?? record.laneNumber;
  const linkIdRaw = record.LINK_ID ?? record.linkId;
  const directionRaw =
    record.DIRECTION_CATEGORY_DESC ??
    record.directionCategoryDesc ??
    record.LANE_TYPE_DESC ??
    record.laneTypeDesc;

  const laneNumber = Number(laneNumberRaw);
  if (
    Number.isFinite(laneNumber) &&
    linkIdRaw != null &&
    typeof directionRaw === "string"
  ) {
    output.push({
      linkId: String(linkIdRaw),
      laneNumber,
      directionText: directionRaw,
      travelDirection:
        typeof (record.LANE_TRAVEL_DIRECTION ?? record.laneTravelDirection) === "string"
          ? String(record.LANE_TRAVEL_DIRECTION ?? record.laneTravelDirection)
          : null,
    });
  }

  Object.values(record).forEach((child) => {
    if (child && typeof child === "object") collectLaneRows(child, output);
  });
  return output;
}

function directionsFromText(text: string): string[] {
  const normalized = text.toLowerCase();
  const directions: string[] = [];
  if (normalized.includes("u-turn") || normalized.includes("uturn")) directions.push("UTurn");
  if (normalized.includes("slight left")) directions.push("SlightLeft");
  else if (normalized.includes("left")) directions.push("Left");
  if (normalized.includes("slight right")) directions.push("SlightRight");
  else if (normalized.includes("right")) directions.push("Right");
  if (
    normalized.includes("straight") ||
    normalized.includes("through") ||
    normalized.includes("forward")
  ) directions.push("Straight");
  return directions.length > 0 ? Array.from(new Set(directions)) : ["Unknown"];
}

function isCompatible(direction: string, maneuverType: string | null | undefined): boolean {
  switch (maneuverType) {
    case "TurnLeft":
    case "SharpLeft":
      return direction === "Left" || direction === "SlightLeft";
    case "SlightLeft":
      return direction === "SlightLeft" || direction === "Left" || direction === "Straight";
    case "TurnRight":
    case "SharpRight":
    case "Ramp":
      return direction === "Right" || direction === "SlightRight";
    case "SlightRight":
      return direction === "SlightRight" || direction === "Right" || direction === "Straight";
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

function normalizeGuidance(rows: LaneRow[], maneuverType: string | null | undefined) {
  if (rows.length === 0) return null;

  // Route Matching returns link attributes in path order. Use the final link group carrying real
  // lane rows: it is the approach road immediately before the supplied maneuver trace endpoint.
  const groups = new Map<string, LaneRow[]>();
  const order: string[] = [];
  for (const row of rows) {
    if (!groups.has(row.linkId)) {
      groups.set(row.linkId, []);
      order.push(row.linkId);
    }
    groups.get(row.linkId)!.push(row);
  }
  const approach = groups.get(order[order.length - 1]) ?? [];
  if (approach.length === 0) return null;

  approach.sort((a, b) => a.laneNumber - b.laneNumber);
  const lanes = approach.map((row) => {
    const directions = directionsFromText(row.directionText);
    return {
      laneNumber: row.laneNumber,
      directions,
      recommended: directions.some((direction) => isCompatible(direction, maneuverType)),
    };
  });

  const recommendedIndexes = lanes
    .map((lane, index) => lane.recommended ? index : -1)
    .filter((index) => index >= 0);

  // A provider row with no maneuver-compatible lane is not enough evidence to tell a driver which
  // lane to use. Return null rather than guessing.
  if (recommendedIndexes.length === 0) return null;

  return {
    lanes,
    recommendedLaneIndexes: recommendedIndexes,
    maneuverRelation: maneuverType ?? null,
    source: "HERE Route Matching / LANE_FCn",
  };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!authorized(req)) return json({ error: "unauthorized" }, 401);

  let body: LaneRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const trace = Array.isArray(body.trace) ? body.trace.filter(validCoordinate) : [];
  if (trace.length < 2) return json({ guidance: null, capability: "insufficient_trace" });

  const hereApiKey = Deno.env.get("HERE_API_KEY")?.trim();
  if (!hereApiKey) return json({ error: "lane_provider_not_configured" }, 503);

  const csv = [
    "LATITUDE,LONGITUDE",
    ...trace.map((point) => point.latitude + "," + point.longitude),
  ].join("\n");

  const params = new URLSearchParams({
    routeMatch: "1",
    mode: "fastest;car;traffic:disabled",
    legal: "access,oneway,thrutraf,turn",
    attributes: "LANE_FCN(*)",
    apiKey: hereApiKey,
  });

  try {
    const response = await fetch(
      "https://routematching.hereapi.com/v8/match/routelinks?" + params.toString(),
      {
        method: "POST",
        headers: {
          "accept": "application/json",
          "content-type": "application/octet-stream",
        },
        body: csv,
        signal: AbortSignal.timeout(7500),
      },
    );

    if (response.status === 401 || response.status === 403) {
      return json({ guidance: null, capability: "provider_access_required" });
    }
    if (response.status === 429) return json({ error: "rate_limited" }, 429);
    if (!response.ok) return json({ error: "lane_upstream_" + response.status }, 503);

    const payload = await response.json();
    const rows = collectLaneRows(payload);
    const guidance = normalizeGuidance(rows, body.maneuverType);

    return json({
      provider: "here",
      capability: guidance ? "available" : "no_trustworthy_lane_guidance",
      guidance,
    });
  } catch {
    return json({ error: "lane_upstream_unavailable" }, 503);
  }
});
