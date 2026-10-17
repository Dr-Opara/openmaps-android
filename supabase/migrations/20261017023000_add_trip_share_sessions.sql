create table if not exists public.trip_share_sessions (
  id uuid primary key default gen_random_uuid(),
  owner_token_hash text not null,
  share_token_hash text not null unique,
  destination_name text not null,
  destination_latitude double precision not null,
  destination_longitude double precision not null,
  current_latitude double precision not null,
  current_longitude double precision not null,
  heading_degrees double precision,
  speed_meters_per_second double precision,
  remaining_distance_meters double precision,
  remaining_duration_seconds double precision,
  status text not null default 'active'
    check (status in ('active','arrived','stopped')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null,
  arrived_at timestamptz,
  stopped_at timestamptz
);

alter table public.trip_share_sessions enable row level security;
revoke all on public.trip_share_sessions from anon, authenticated;

create index if not exists trip_share_sessions_share_token_idx
  on public.trip_share_sessions (share_token_hash);

create index if not exists trip_share_sessions_expires_at_idx
  on public.trip_share_sessions (expires_at);

create or replace function public.cleanup_expired_trip_shares()
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.trip_share_sessions
  where expires_at < now() - interval '24 hours'
     or stopped_at < now() - interval '24 hours';
$$;

revoke execute on function public.cleanup_expired_trip_shares()
  from public, anon, authenticated;
grant execute on function public.cleanup_expired_trip_shares()
  to service_role;
