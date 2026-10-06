-- FitFam initial schema.
-- Structure only: no business data lives in this (public) file.
-- Run once on an empty database (Supabase SQL Editor, or Flyway later).

create table users (
    id             uuid primary key default gen_random_uuid(),
    email          text not null unique,
    role           text not null default 'customer',
    status         text not null default 'invited',
    display_name   text,
    avatar_url     text,
    created_at     timestamptz not null default now(),
    first_login_at timestamptz,
    constraint users_email_normalized check (email = lower(btrim(email))),
    constraint users_role_valid       check (role in ('customer', 'admin')),
    constraint users_status_valid     check (status in ('invited', 'active'))
);

create table plans (
    id         uuid primary key default gen_random_uuid(),
    slug       text not null unique,
    name_he    text,
    sort_order integer not null
);

create table plan_levels (
    id           uuid primary key default gen_random_uuid(),
    plan_id      uuid not null references plans (id),
    level_number integer not null,
    slug         text not null,
    name_he      text,
    constraint plan_levels_number_positive check (level_number >= 1),
    constraint plan_levels_plan_number_key unique (plan_id, level_number),
    constraint plan_levels_plan_slug_key   unique (plan_id, slug),
    -- lets enrollments prove a level belongs to the enrolled plan
    constraint plan_levels_id_plan_key     unique (id, plan_id)
);

-- Semi-schema: structure now, content columns can be added later.
create table workouts (
    id             uuid primary key default gen_random_uuid(),
    level_id       uuid not null references plan_levels (id),
    sort_order     integer not null,
    type           text not null default 'regular',
    title_he       text,
    description_he text,
    details        jsonb not null default '{}'::jsonb,
    created_at     timestamptz not null default now(),
    constraint workouts_type_valid     check (type in ('regular', 'challenge')),
    constraint workouts_sort_positive  check (sort_order >= 1),
    constraint workouts_level_sort_key unique (level_id, sort_order) deferrable initially deferred
);

create table enrollments (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid not null references users (id) on delete cascade,
    plan_id          uuid not null references plans (id),
    current_level_id uuid not null,
    status           text not null default 'active',
    started_at       timestamptz not null default now(),
    ended_at         timestamptz,
    constraint enrollments_status_valid check (status in ('active', 'paused', 'ended')),
    constraint enrollments_level_in_plan
        foreign key (current_level_id, plan_id) references plan_levels (id, plan_id)
);

-- One live (not ended) enrollment per user and plan; several plans per user are allowed.
create unique index enrollments_one_live_per_user_plan
    on enrollments (user_id, plan_id) where status <> 'ended';
create index enrollments_user_idx on enrollments (user_id);

create table enrollment_level_history (
    id            uuid primary key default gen_random_uuid(),
    enrollment_id uuid not null references enrollments (id) on delete cascade,
    from_level_id uuid references plan_levels (id),
    to_level_id   uuid not null references plan_levels (id),
    reason        text not null,
    changed_by    uuid references users (id) on delete set null,
    note          text,
    changed_at    timestamptz not null default now(),
    constraint level_history_reason_valid
        check (reason in ('assigned', 'challenge_passed', 'skipped', 'admin_change'))
);
create index level_history_enrollment_idx on enrollment_level_history (enrollment_id);

create table admin_audit_log (
    id            bigint generated always as identity primary key,
    admin_user_id uuid references users (id) on delete set null,
    action        text not null,
    target_type   text,
    target_id     text,
    details       jsonb not null default '{}'::jsonb,
    created_at    timestamptz not null default now()
);

-- Row Level Security on every table, with NO policies: only the API's
-- database owner connection can read or write.
alter table users                    enable row level security;
alter table plans                    enable row level security;
alter table plan_levels              enable row level security;
alter table workouts                 enable row level security;
alter table enrollments              enable row level security;
alter table enrollment_level_history enable row level security;
alter table admin_audit_log          enable row level security;

-- Defence in depth on Supabase: the public API roles get no table access at all.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        revoke all on all tables in schema public from anon;
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        revoke all on all tables in schema public from authenticated;
    end if;
end
$$;
