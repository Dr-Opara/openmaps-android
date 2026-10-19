create table if not exists public.search_cache (
  cache_key text primary key,
  response jsonb not null,
  providers text[] not null default '{}',
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create index if not exists search_cache_expires_at_idx
  on public.search_cache (expires_at);

alter table public.search_cache enable row level security;

create table if not exists public.search_telemetry (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  query_hash text not null,
  query_type text not null,
  result_count integer not null default 0,
  providers text[] not null default '{}',
  used_cache boolean not null default false,
  latency_ms integer not null,
  had_location_bias boolean not null default false,
  locale_tag text,
  outcome text not null,
  fallback_used boolean not null default false
);

create index if not exists search_telemetry_occurred_at_idx
  on public.search_telemetry (occurred_at desc);

create index if not exists search_telemetry_outcome_idx
  on public.search_telemetry (outcome, occurred_at desc);

alter table public.search_telemetry enable row level security;

create or replace view public.search_health_24h as
select
  count(*)::bigint as requests,
  count(*) filter (where outcome = 'success')::bigint as successes,
  count(*) filter (where outcome = 'zero_results')::bigint as zero_results,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms,
  percentile_cont(0.95) within group (order by latency_ms) as p95_latency_ms,
  count(*) filter (where fallback_used)::bigint as fallback_requests,
  count(*) filter (where used_cache)::bigint as cache_hits
from public.search_telemetry
where occurred_at >= now() - interval '24 hours';

revoke all on public.search_cache from anon, authenticated;
revoke all on public.search_telemetry from anon, authenticated;
revoke all on public.search_health_24h from anon, authenticated;
