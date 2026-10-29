-- Proof of what each customer accepted and when: one row per acceptance (append-only; the newest row of a kind counts).
-- kind 'terms'              : terms of use + privacy policy; details = {termsVersion, privacyVersion, adult}
-- kind 'health_declaration' : health declaration; details = answers, medical clearance, confirmations, typed full name;
--                             expires_at = when a new declaration is due.
-- Rows are deleted with the user (account deletion).
create table user_consents (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users (id) on delete cascade,
    kind        text not null,
    version     text not null,
    accepted_at timestamptz not null default now(),
    expires_at  timestamptz,
    details     jsonb not null default '{}'::jsonb,
    constraint user_consents_kind_valid check (kind in ('terms', 'health_declaration'))
);
create index user_consents_user_idx on user_consents (user_id, kind, accepted_at desc);

alter table user_consents enable row level security;
