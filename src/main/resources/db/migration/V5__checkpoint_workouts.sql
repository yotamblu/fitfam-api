-- A checkpoint is a normal workout that marks the end of a phase inside a level (shown with a star).
-- It follows every rule of a regular workout; only the type differs.
alter table workouts drop constraint workouts_type_valid;
alter table workouts add constraint workouts_type_valid check (type in ('regular', 'checkpoint', 'challenge'));
