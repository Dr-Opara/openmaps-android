create table if not exists public.places_telemetry (
  id bigint generated always as identity primary key,
  created_at timestamptz not null default now(),
  categories text[] not null default '{}',
  radius_meters integer not null,
  result_count integer not null default 0,
  providers text[] not null default '{}',
  latency_ms integer not null default 0,
  outcome text not null check (outcome in ('success','zero_results','error')),
  fallback_used boolean not null default false
);

alter table public.places_telemetry enable row level security;

revoke all on public.places_telemetry from anon, authenticated;

create index if not exists places_telemetry_created_at_idx
  on public.places_telemetry (created_at desc);

create or replace view public.places_health_24h
with (security_invoker = false)
as
select
  count(*)::bigint as requests,
  count(*) filter (where outcome = 'success')::bigint as successes,
  count(*) filter (where outcome = 'zero_results')::bigint as zero_results,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms,
  percentile_cont(0.95) within group (order by latency_ms) as p95_latency_ms,
  count(*) filter (where fallback_used)::bigint as fallback_requests,
  count(*) filter (where 'here' = any(providers))::bigint as here_requests,
  count(*) filter (where 'osm' = any(providers))::bigint as osm_requests
from public.places_telemetry
where created_at >= now() - interval '24 hours';

revoke all on public.places_health_24h from anon, authenticated;
