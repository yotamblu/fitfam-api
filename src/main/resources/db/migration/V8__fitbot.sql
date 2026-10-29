-- Fitbot, the AI training assistant.
-- chat_threads / chat_messages: one customer's conversations (deleted with the user, and by the retention job).
-- fitbot_knowledge: named text sections written and edited by the coaches in the admin app; they are added to the
--                   model's instructions. The content lives only in the database, never in git.
-- user_consents gets a third kind, 'ai_chat': the customer agreed that chats are processed by an AI provider.

create table chat_threads (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users (id) on delete cascade,
    title        text not null,
    -- 'fallback' = the first words of the first message; 'auto' = written by the model; 'user' = renamed by the customer
    title_source text not null default 'fallback',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint chat_threads_title_source_valid check (title_source in ('fallback', 'auto', 'user'))
);
create index chat_threads_user_idx on chat_threads (user_id, updated_at desc);
create index chat_threads_updated_idx on chat_threads (updated_at);

create table chat_messages (
    id            uuid primary key default gen_random_uuid(),
    thread_id     uuid not null references chat_threads (id) on delete cascade,
    user_id       uuid not null references users (id) on delete cascade,
    role          text not null,
    content       text not null,
    -- buttons the reply offers (for example open a workout): [{"type":"open_workout","workoutId":"...","title":"..."}]
    actions       jsonb not null default '[]'::jsonb,
    model         text,
    input_tokens  integer,
    output_tokens integer,
    created_at    timestamptz not null default now(),
    constraint chat_messages_role_valid check (role in ('user', 'assistant'))
);
create index chat_messages_thread_idx on chat_messages (thread_id, created_at);
create index chat_messages_user_day_idx on chat_messages (user_id, created_at desc) where role = 'user';

create table fitbot_knowledge (
    id         uuid primary key default gen_random_uuid(),
    slug       text not null unique,
    title_he   text not null,
    content_he text not null,
    enabled    boolean not null default true,
    sort_order integer not null default 0,
    updated_at timestamptz not null default now(),
    updated_by uuid references users (id) on delete set null
);

alter table user_consents drop constraint user_consents_kind_valid;
alter table user_consents add constraint user_consents_kind_valid
    check (kind in ('terms', 'health_declaration', 'ai_chat'));

alter table chat_threads enable row level security;
alter table chat_messages enable row level security;
alter table fitbot_knowledge enable row level security;
