-- Exercise bank, workout content and customer progress.
--
-- Structure only (no business data). Exercises are entered by coaches through the admin app. Workout content lives in
-- workouts.details as a JSON tree that the API validates; the API is the only thing that reads or writes these tables.

-- Exercise bank. Running/swimming "activities" are rows here too; their intensity is set on the workout line.
create table exercises (
    id             uuid primary key default gen_random_uuid(),
    name_he        text not null,
    sport          text not null,
    measure        text not null,
    equipment      jsonb not null default '[]'::jsonb,
    muscle_groups  jsonb not null default '[]'::jsonb,
    description_he text,
    cues_he        text,
    -- Optional YouTube video id (11 chars). The API extracts it from the link a coach pastes.
    youtube_id     text,
    archived       boolean not null default false,
    created_by     uuid references users (id) on delete set null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint exercises_sport_valid   check (sport in ('running', 'swimming', 'gym', 'calisthenics')),
    constraint exercises_measure_valid check (measure in ('reps', 'hold_time', 'distance', 'duration', 'calories', 'max_effort')),
    constraint exercises_equipment_array check (jsonb_typeof(equipment) = 'array'),
    constraint exercises_muscles_array   check (jsonb_typeof(muscle_groups) = 'array'),
    constraint exercises_youtube_id_valid check (youtube_id is null or youtube_id ~ '^[A-Za-z0-9_-]{11}$'),
    constraint exercises_sport_name_key  unique (sport, name_he)
);

-- Workouts: the table from V1 gains the fields the coaches' editor needs.
alter table workouts
    add column sport           text not null,
    add column status          text not null default 'draft',
    add column goal_he         text,
    add column est_minutes     integer,
    add column content_version integer not null default 1,
    add column updated_at      timestamptz not null default now(),
    add column updated_by      uuid references users (id) on delete set null;

alter table workouts
    add constraint workouts_sport_valid  check (sport in ('running', 'swimming', 'gym', 'calisthenics')),
    add constraint workouts_status_valid check (status in ('draft', 'published', 'archived')),
    add constraint workouts_minutes_valid check (est_minutes is null or est_minutes between 1 and 600);

-- At most one live (not archived) challenge per level.
create unique index workouts_one_challenge_per_level on workouts (level_id)
    where type = 'challenge' and status <> 'archived';

-- Where a customer is in a workout, and which ones they finished. Skipped levels never get rows here, so points and
-- streaks (built later from completed rows) cannot count them.
create table workout_progress (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users (id) on delete cascade,
    enrollment_id uuid not null references enrollments (id) on delete cascade,
    workout_id    uuid not null references workouts (id),
    status        text not null default 'in_progress',
    resume_step   integer not null default 0,
    started_at    timestamptz not null default now(),
    completed_at  timestamptz,
    constraint workout_progress_status_valid check (status in ('in_progress', 'completed')),
    constraint workout_progress_step_valid   check (resume_step >= 0),
    constraint workout_progress_key unique (enrollment_id, workout_id)
);
create index workout_progress_user_idx on workout_progress (user_id);

-- Self-declared challenge results. Retries are unlimited, so every attempt is a row.
create table challenge_attempts (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users (id) on delete cascade,
    enrollment_id uuid references enrollments (id) on delete cascade,
    workout_id    uuid not null references workouts (id),
    passed        boolean not null,
    attempted_at  timestamptz not null default now()
);
create index challenge_attempts_user_idx on challenge_attempts (user_id, workout_id);

-- Same rule as every table: RLS on, no policies, no grants for the public roles (V2 default privileges cover grants).
alter table exercises          enable row level security;
alter table workout_progress   enable row level security;
alter table challenge_attempts enable row level security;
