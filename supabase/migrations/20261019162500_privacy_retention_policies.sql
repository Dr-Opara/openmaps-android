create extension if not exists pg_cron with schema pg_catalog;

create or replace function public.cleanup_openmaps_retention()
returns jsonb
language plpgsql
security invoker
set search_path = public, pg_catalog
as $$
declare
  deleted_search_cache bigint := 0;
  deleted_places_cache bigint := 0;
  deleted_search_limits bigint := 0;
  deleted_navigation_limits bigint := 0;
  deleted_trip_sessions bigint := 0;
  deleted_community_reports bigint := 0;
  deleted_search_telemetry bigint := 0;
  deleted_routing_telemetry bigint := 0;
  deleted_traffic_telemetry bigint := 0;
  deleted_places_telemetry bigint := 0;
  deleted_trip_telemetry bigint := 0;
  deleted_community_telemetry bigint := 0;
  deleted_voice_telemetry bigint := 0;
  deleted_reliability bigint := 0;
begin
  delete from public.search_cache where expires_at < now();
  get diagnostics deleted_search_cache = row_count;

  delete from public.places_cache where expires_at < now();
  get diagnostics deleted_places_cache = row_count;

  delete from public.search_rate_limits where bucket_start < now() - interval '2 days';
  get diagnostics deleted_search_limits = row_count;

  delete from public.navigation_rate_limits where bucket_start < now() - interval '2 days';
  get diagnostics deleted_navigation_limits = row_count;

  delete from public.trip_share_sessions
  where greatest(
    expires_at,
    coalesce(arrived_at, '-infinity'::timestamptz),
    coalesce(stopped_at, '-infinity'::timestamptz),
    updated_at
  ) < now() - interval '24 hours';
  get diagnostics deleted_trip_sessions = row_count;

  delete from public.community_reports where expires_at < now() - interval '7 days';
  get diagnostics deleted_community_reports = row_count;

  delete from public.search_telemetry where occurred_at < now() - interval '30 days';
  get diagnostics deleted_search_telemetry = row_count;

  delete from public.routing_telemetry where occurred_at < now() - interval '30 days';
  get diagnostics deleted_routing_telemetry = row_count;

  delete from public.traffic_telemetry where occurred_at < now() - interval '30 days';
  get diagnostics deleted_traffic_telemetry = row_count;

  delete from public.places_telemetry where created_at < now() - interval '30 days';
  get diagnostics deleted_places_telemetry = row_count;

  delete from public.trip_share_telemetry where occurred_at < now() - interval '30 days';
  get diagnostics deleted_trip_telemetry = row_count;

  delete from public.community_report_telemetry where occurred_at < now() - interval '30 days';
  get diagnostics deleted_community_telemetry = row_count;

  delete from public.voice_telemetry where occurred_at < now() - interval '30 days';
  get diagnostics deleted_voice_telemetry = row_count;

  delete from public.mobile_reliability_events where occurred_at < now() - interval '30 days';
  get diagnostics deleted_reliability = row_count;

  delete from cron.job_run_details where end_time < now() - interval '14 days';

  return jsonb_build_object(
    'search_cache', deleted_search_cache,
    'places_cache', deleted_places_cache,
    'search_rate_limits', deleted_search_limits,
    'navigation_rate_limits', deleted_navigation_limits,
    'trip_share_sessions', deleted_trip_sessions,
    'community_reports', deleted_community_reports,
    'search_telemetry', deleted_search_telemetry,
    'routing_telemetry', deleted_routing_telemetry,
    'traffic_telemetry', deleted_traffic_telemetry,
    'places_telemetry', deleted_places_telemetry,
    'trip_share_telemetry', deleted_trip_telemetry,
    'community_report_telemetry', deleted_community_telemetry,
    'voice_telemetry', deleted_voice_telemetry,
    'mobile_reliability_events', deleted_reliability
  );
end;
$$;

revoke all on function public.cleanup_openmaps_retention() from public, anon, authenticated;
grant execute on function public.cleanup_openmaps_retention() to service_role;

do $$
declare
  existing_job bigint;
begin
  select jobid into existing_job
  from cron.job
  where jobname = 'openmaps-daily-retention-cleanup'
  limit 1;

  if existing_job is not null then
    perform cron.unschedule(existing_job);
  end if;

  perform cron.schedule(
    'openmaps-daily-retention-cleanup',
    '17 4 * * *',
    'select public.cleanup_openmaps_retention();'
  );
end $$;
