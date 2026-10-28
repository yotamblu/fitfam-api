-- The customer's latest 5 km time. Personal running paces are computed from it by the API and never stored.
-- Seconds for the whole 5 km (12:00 to 70:00); null = not set yet.
alter table users
    add column five_k_sec        integer,
    add column five_k_updated_at timestamptz,
    add constraint users_five_k_range check (five_k_sec is null or five_k_sec between 720 and 4200);
