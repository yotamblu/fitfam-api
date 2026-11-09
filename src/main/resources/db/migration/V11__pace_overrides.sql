-- A customer's own pace ranges (seconds per km), set after the 5 km time is entered. One row per pace kind; a kind with
-- no row uses the calculated range. The rows are removed when the 5 km time changes.
create table pace_overrides (
    user_id      uuid        not null references users (id) on delete cascade,
    kind         text        not null check (kind in ('easy', 'threshold', 'interval')),
    fast_sec     integer     not null check (fast_sec between 120 and 1200),
    slow_sec     integer     not null check (slow_sec between 120 and 1200),
    updated_at   timestamptz not null default now(),
    primary key (user_id, kind),
    check (fast_sec <= slow_sec)
);

alter table pace_overrides enable row level security;
