create table if not exists public.voice_telemetry (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  language text not null,
  outcome text not null check (outcome in ('success','error','unconfigured')),
  latency_ms integer not null default 0,
  audio_bytes integer not null default 0
);

alter table public.voice_telemetry enable row level security;
revoke all on public.voice_telemetry from anon, authenticated;

create index if not exists voice_telemetry_occurred_at_idx
  on public.voice_telemetry (occurred_at desc);

create or replace view public.voice_health_24h
with (security_invoker = false)
as
select
  count(*)::bigint as requests,
  count(*) filter (where outcome = 'success')::bigint as successes,
  count(*) filter (where outcome = 'error')::bigint as errors,
  count(*) filter (where outcome = 'unconfigured')::bigint as unconfigured,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms,
  percentile_cont(0.95) within group (order by latency_ms)::integer as p95_latency_ms,
  coalesce(sum(audio_bytes), 0)::bigint as audio_bytes
from public.voice_telemetry
where occurred_at >= now() - interval '24 hours';

revoke all on public.voice_health_24h from anon, authenticated;
