alter table public.routing_telemetry
  add column if not exists fallback_used boolean not null default false;

create or replace view public.routing_health_24h as
select
  count(*)::bigint as requests,
  count(*) filter (where outcome = 'success')::bigint as successes,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms,
  percentile_cont(0.95) within group (order by latency_ms) as p95_latency_ms,
  round(avg(alternatives_count)::numeric, 2) as avg_alternatives,
  count(*) filter (where traffic_aware)::bigint as traffic_aware_requests,
  count(*) filter (where fallback_used)::bigint as fallback_uses
from public.routing_telemetry
where occurred_at >= now() - interval '24 hours';
