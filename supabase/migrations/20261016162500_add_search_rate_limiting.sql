create table if not exists public.search_rate_limits (
  client_hash text not null,
  bucket_start timestamptz not null,
  request_count integer not null default 0,
  primary key (client_hash, bucket_start)
);

alter table public.search_rate_limits enable row level security;
revoke all on public.search_rate_limits from anon, authenticated;

create or replace function public.consume_search_rate_limit(
  p_client_hash text,
  p_bucket_start timestamptz,
  p_limit integer
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_count integer;
begin
  insert into public.search_rate_limits(client_hash, bucket_start, request_count)
  values (p_client_hash, p_bucket_start, 1)
  on conflict (client_hash, bucket_start)
  do update set request_count = public.search_rate_limits.request_count + 1
  returning request_count into v_count;

  return v_count <= p_limit;
end;
$$;

create or replace function public.cleanup_search_runtime_data()
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.search_cache where expires_at < now();
  delete from public.search_rate_limits where bucket_start < now() - interval '10 minutes';
  delete from public.search_telemetry where occurred_at < now() - interval '30 days';
$$;

revoke execute on function public.consume_search_rate_limit(text, timestamptz, integer)
  from public, anon, authenticated;
grant execute on function public.consume_search_rate_limit(text, timestamptz, integer)
  to service_role;

revoke execute on function public.cleanup_search_runtime_data()
  from public, anon, authenticated;
grant execute on function public.cleanup_search_runtime_data()
  to service_role;
