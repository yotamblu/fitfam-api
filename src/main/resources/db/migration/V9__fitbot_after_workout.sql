-- A Fitbot conversation that was opened from the end of a workout points at that workout (one such conversation per
-- customer and workout). The workout's details are given to the model with every message of that conversation.
alter table chat_threads add column workout_id uuid references workouts (id) on delete set null;
create unique index chat_threads_workout_unique on chat_threads (user_id, workout_id) where workout_id is not null;
