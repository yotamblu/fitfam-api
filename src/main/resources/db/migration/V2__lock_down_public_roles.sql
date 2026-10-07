-- Lock down Supabase's public-facing roles (anon, authenticated).
--
-- Only this API (the database owner) may touch our tables. Supabase also exposes the public schema through its own
-- REST endpoint to the anon/authenticated roles, so those roles get NO table, sequence or function access at all, now
-- and for anything created later. Row Level Security stays on as a second layer.
--
-- Not done here on purpose: enabling Row Level Security on Flyway's own history table. Flyway holds a lock on that
-- table while a migration runs, so altering it from inside a migration waits forever (statement timeout). On a new
-- database run this once, outside Flyway, while the API is stopped:
--     alter table public.flyway_schema_history enable row level security;
-- (The revokes below already cut that table off from the public roles.)

do $$
declare
    r text;
begin
    foreach r in array array['anon', 'authenticated'] loop
        if exists (select 1 from pg_roles where rolname = r) then
            execute format('revoke all on all tables in schema public from %I', r);
            execute format('revoke all on all sequences in schema public from %I', r);
            execute format('revoke all on all functions in schema public from %I', r);
            execute format('alter default privileges in schema public revoke all on tables from %I', r);
            execute format('alter default privileges in schema public revoke all on sequences from %I', r);
            execute format('alter default privileges in schema public revoke all on functions from %I', r);
        end if;
    end loop;
end
$$;
