create table if not exists public.routing_telemetry (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  outcome text not null,
  latency_ms integer not null,
  alternatives_count integer not null default 0,
  traffic_aware boolean not null default false,
  traffic_delay_seconds double precision,
  provider text,
  route_distance_meters double precision,
  route_duration_seconds double precision
);

create index if not exists routing_telemetry_occurred_at_idx
  on public.routing_telemetry (occurred_at desc);

alter table public.routing_telemetry enable row level security;
revoke all on public.routing_telemetry from anon, authenticated;

create table if not exists public.traffic_telemetry (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  outcome text not null,
  latency_ms integer not null,
  incidents_count integer not null default 0,
  provider text,
  route_points integer not null default 0
);

create index if not exists traffic_telemetry_occurred_at_idx
  on public.traffic_telemetry (occurred_at desc);

alter table public.traffic_telemetry enable row level security;
revoke all on public.traffic_telemetry from anon, authenticated;

create or replace view public.routing_health_24h as
select
  count(*)::bigint as requests,
  count(*) filter (where outcome = 'success')::bigint as successes,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms,
  percentile_cont(0.95) within group (order by latency_ms) as p95_latency_ms,
  round(avg(alternatives_count)::numeric, 2) as avg_alternatives,
  count(*) filter (where traffic_aware)::bigint as traffic_aware_requests
from public.routing_telemetry
where occurred_at >= now() - interval '24 hours';

create or replace view public.traffic_health_24h as
select
  count(*)::bigint as requests,
  count(*) filter (where outcome = 'success')::bigint as successes,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms,
  percentile_cont(0.95) within group (order by latency_ms) as p95_latency_ms,
  round(avg(incidents_count)::numeric, 2) as avg_incidents
from public.traffic_telemetry
where occurred_at >= now() - interval '24 hours';

revoke all on public.routing_health_24h from anon, authenticated;
revoke all on public.traffic_health_24h from anon, authenticated;
