create table if not exists public.trip_share_telemetry (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  event text not null check (event in ('create','update','view','arrive','stop','error')),
  outcome text not null check (outcome in ('success','error')),
  latency_ms integer not null default 0
);

alter table public.trip_share_telemetry enable row level security;
revoke all on public.trip_share_telemetry from anon, authenticated;

create index if not exists trip_share_telemetry_occurred_at_idx
  on public.trip_share_telemetry (occurred_at desc);

create or replace view public.trip_share_health_24h
with (security_invoker = false)
as
select
  count(*)::bigint as events,
  count(*) filter (where event = 'create' and outcome = 'success')::bigint as sessions_created,
  count(*) filter (where event = 'update' and outcome = 'success')::bigint as updates,
  count(*) filter (where event = 'view' and outcome = 'success')::bigint as viewer_reads,
  count(*) filter (where event = 'arrive' and outcome = 'success')::bigint as arrivals,
  count(*) filter (where event = 'stop' and outcome = 'success')::bigint as stops,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms
from public.trip_share_telemetry
where occurred_at >= now() - interval '24 hours';

revoke all on public.trip_share_health_24h from anon, authenticated;
