create table if not exists public.mobile_reliability_events (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  client_hash text not null check (char_length(client_hash) = 64),
  event text not null check (event in (
    'app_launch','previous_crash','route_request','route_ready','route_failed',
    'navigation_started','navigation_start_failed','off_route_confirmed',
    'reroute_triggered','reroute_succeeded','reroute_failed','arrived',
    'navigation_ended','ar_availability','ar_ready','ar_fallback','camera_failure'
  )),
  outcome text not null check (outcome in ('info','success','error','degraded')),
  reason text check (reason is null or char_length(reason) <= 120),
  app_version text not null check (char_length(app_version) <= 64),
  app_build integer not null check (app_build >= 0),
  device_api integer not null check (device_api >= 1),
  duration_ms integer check (duration_ms is null or duration_ms >= 0),
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object')
);

alter table public.mobile_reliability_events enable row level security;
revoke all on public.mobile_reliability_events from anon, authenticated;

create index if not exists mobile_reliability_events_occurred_at_idx
  on public.mobile_reliability_events (occurred_at desc);
create index if not exists mobile_reliability_events_event_occurred_at_idx
  on public.mobile_reliability_events (event, occurred_at desc);
create index if not exists mobile_reliability_events_client_hash_occurred_at_idx
  on public.mobile_reliability_events (client_hash, occurred_at desc);

create or replace view public.mobile_reliability_health_24h
with (security_invoker = false)
as
select
  count(*)::bigint as events,
  count(*) filter (where event = 'app_launch')::bigint as app_launches,
  count(*) filter (where event = 'previous_crash')::bigint as crashes,
  count(distinct client_hash) filter (where event = 'previous_crash')::bigint as crash_affected_clients,
  round(case when count(*) filter (where event = 'app_launch') = 0 then null
    else 100.0 * count(*) filter (where event = 'previous_crash')
      / count(*) filter (where event = 'app_launch') end, 2) as crashes_per_100_launches,
  count(*) filter (where event = 'route_request')::bigint as route_requests,
  count(*) filter (where event = 'route_ready')::bigint as route_successes,
  count(*) filter (where event = 'route_failed')::bigint as route_failures,
  round(case when count(*) filter (where event in ('route_ready','route_failed')) = 0 then null
    else 100.0 * count(*) filter (where event = 'route_ready')
      / count(*) filter (where event in ('route_ready','route_failed')) end, 2) as route_success_rate_pct,
  count(*) filter (where event = 'navigation_started')::bigint as navigation_starts,
  count(*) filter (where event = 'navigation_start_failed')::bigint as navigation_start_failures,
  count(*) filter (where event = 'off_route_confirmed')::bigint as off_route_events,
  count(*) filter (where event = 'reroute_triggered')::bigint as reroutes_triggered,
  count(*) filter (where event = 'reroute_succeeded')::bigint as reroute_successes,
  count(*) filter (where event = 'reroute_failed')::bigint as reroute_failures,
  round(case when count(*) filter (where event in ('reroute_succeeded','reroute_failed')) = 0 then null
    else 100.0 * count(*) filter (where event = 'reroute_succeeded')
      / count(*) filter (where event in ('reroute_succeeded','reroute_failed')) end, 2) as reroute_success_rate_pct,
  count(*) filter (where event = 'arrived')::bigint as arrivals,
  count(*) filter (where event = 'navigation_ended')::bigint as navigation_ends,
  count(*) filter (where event = 'ar_availability')::bigint as ar_availability_checks,
  count(*) filter (where event = 'ar_ready')::bigint as ar_ready_events,
  count(*) filter (where event = 'ar_fallback')::bigint as ar_fallbacks,
  count(*) filter (where event = 'camera_failure')::bigint as camera_failures,
  round(avg(duration_ms) filter (where duration_ms is not null)::numeric, 1) as avg_duration_ms,
  percentile_cont(0.95) within group (order by duration_ms)
    filter (where duration_ms is not null)::integer as p95_duration_ms
from public.mobile_reliability_events
where occurred_at >= now() - interval '24 hours';

revoke all on public.mobile_reliability_health_24h from anon, authenticated;

create or replace view public.operations_health_24h
with (security_invoker = false)
as
select
  (select to_jsonb(v) from public.search_health_24h v) as search,
  (select to_jsonb(v) from public.routing_health_24h v) as routing,
  (select to_jsonb(v) from public.traffic_health_24h v) as traffic,
  (select to_jsonb(v) from public.places_health_24h v) as places,
  (select to_jsonb(v) from public.trip_share_health_24h v) as trip_sharing,
  (select to_jsonb(v) from public.community_report_health_24h v) as community_reports,
  (select to_jsonb(v) from public.voice_health_24h v) as voice,
  (select to_jsonb(v) from public.mobile_reliability_health_24h v) as mobile_reliability;

revoke all on public.operations_health_24h from anon, authenticated;
