-- The optional workout log: the weight and reps a customer chose to write down for one set of one exercise.
--
-- Structure only. A row is one set; a customer may fill in either value or both, and may change it later while the
-- workout is still open for them. The "previous" values shown next to an exercise are the latest earlier workout that
-- has rows for it.
create table set_logs (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users (id) on delete cascade,
    workout_id  uuid not null references workouts (id) on delete cascade,
    exercise_id uuid not null references exercises (id) on delete cascade,
    set_number  integer not null,
    weight_kg   numeric(6, 2),
    reps        integer,
    logged_at   timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint set_logs_set_valid    check (set_number between 1 and 50),
    constraint set_logs_weight_valid check (weight_kg is null or weight_kg between 0 and 1000),
    constraint set_logs_reps_valid   check (reps is null or reps between 0 and 1000),
    constraint set_logs_something    check (weight_kg is not null or reps is not null),
    constraint set_logs_key unique (user_id, workout_id, exercise_id, set_number)
);
create index set_logs_history_idx on set_logs (user_id, exercise_id, updated_at desc);

-- Same rule as every table: RLS on, no policies, no grants for the public roles (V2 default privileges cover grants).
alter table set_logs enable row level security;
