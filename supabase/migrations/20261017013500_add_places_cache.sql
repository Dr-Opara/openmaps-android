create table if not exists public.places_cache (
  cache_key text primary key,
  response jsonb not null,
  providers text[] not null default '{}',
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

alter table public.places_cache enable row level security;
revoke all on public.places_cache from anon, authenticated;

create index if not exists places_cache_expires_at_idx
  on public.places_cache (expires_at);

alter table public.places_telemetry
  add column if not exists used_cache boolean not null default false;

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
  count(*) filter (where used_cache)::bigint as cache_hits,
  count(*) filter (where 'here' = any(providers))::bigint as here_requests,
  count(*) filter (where 'osm' = any(providers))::bigint as osm_requests
from public.places_telemetry
where created_at >= now() - interval '24 hours';

revoke all on public.places_health_24h from anon, authenticated;
