create table if not exists public.community_reports (
  id uuid primary key default gen_random_uuid(),
  report_type text not null check (report_type in (
    'Police','Traffic','Crash','LaneClosed','Construction','RoadHazard','Flooding','DisabledVehicle','ObjectOnRoad'
  )),
  latitude double precision not null,
  longitude double precision not null,
  submitter_hash text not null,
  status text not null default 'active' check (status in ('active','rejected','expired')),
  confirmations integer not null default 0,
  dismissals integer not null default 0,
  confidence double precision not null default 0.35,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null,
  moderation_reason text
);

alter table public.community_reports enable row level security;
revoke all on public.community_reports from anon, authenticated;

create table if not exists public.community_report_votes (
  report_id uuid not null references public.community_reports(id) on delete cascade,
  voter_hash text not null,
  vote text not null check (vote in ('confirm','dismiss')),
  created_at timestamptz not null default now(),
  primary key (report_id, voter_hash)
);

alter table public.community_report_votes enable row level security;
revoke all on public.community_report_votes from anon, authenticated;

create index if not exists community_reports_active_idx
  on public.community_reports (status, expires_at);

create index if not exists community_reports_geo_idx
  on public.community_reports (latitude, longitude);

create or replace function public.recompute_community_report_confidence(p_report_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_confirm integer;
  v_dismiss integer;
  v_confidence double precision;
begin
  select
    count(*) filter (where vote = 'confirm'),
    count(*) filter (where vote = 'dismiss')
  into v_confirm, v_dismiss
  from public.community_report_votes
  where report_id = p_report_id;

  v_confidence := greatest(0.0, least(0.95, 0.35 + (v_confirm * 0.15) - (v_dismiss * 0.20)));

  update public.community_reports
  set confirmations = v_confirm,
      dismissals = v_dismiss,
      confidence = v_confidence,
      status = case when v_dismiss >= 3 and v_dismiss > v_confirm * 2 then 'rejected' else status end,
      updated_at = now()
  where id = p_report_id;
end;
$$;

revoke execute on function public.recompute_community_report_confidence(uuid)
  from public, anon, authenticated;
grant execute on function public.recompute_community_report_confidence(uuid)
  to service_role;

create table if not exists public.community_report_telemetry (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  event text not null check (event in ('submit','query','confirm','dismiss','cluster','error')),
  outcome text not null check (outcome in ('success','error')),
  latency_ms integer not null default 0
);

alter table public.community_report_telemetry enable row level security;
revoke all on public.community_report_telemetry from anon, authenticated;

create or replace view public.community_report_health_24h
with (security_invoker = false)
as
select
  count(*)::bigint as events,
  count(*) filter (where event = 'submit' and outcome = 'success')::bigint as submissions,
  count(*) filter (where event = 'query' and outcome = 'success')::bigint as queries,
  count(*) filter (where event = 'confirm' and outcome = 'success')::bigint as confirmations,
  count(*) filter (where event = 'dismiss' and outcome = 'success')::bigint as dismissals,
  count(*) filter (where event = 'cluster' and outcome = 'success')::bigint as clustered_reports,
  count(*) filter (where outcome = 'error')::bigint as errors,
  round(avg(latency_ms)::numeric, 1) as avg_latency_ms
from public.community_report_telemetry
where occurred_at >= now() - interval '24 hours';

revoke all on public.community_report_health_24h from anon, authenticated;

create or replace function public.cleanup_community_reports()
returns void
language sql
security definer
set search_path = public
as $$
  update public.community_reports
  set status = 'expired', updated_at = now()
  where status = 'active' and expires_at <= now();

  delete from public.community_report_votes
  where report_id in (
    select id from public.community_reports
    where expires_at < now() - interval '7 days'
  );

  delete from public.community_reports
  where expires_at < now() - interval '7 days';

  delete from public.community_report_telemetry
  where occurred_at < now() - interval '30 days';
$$;

revoke execute on function public.cleanup_community_reports()
  from public, anon, authenticated;
grant execute on function public.cleanup_community_reports()
  to service_role;
