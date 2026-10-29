-- Plans whose customers are asked for a 5 km time (personal running paces), even before the plan has running workouts.
-- Which plans get the flag is set by hand in the database.
alter table plans add column uses_paces boolean not null default false;
