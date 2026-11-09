-- When the customer finished (or skipped) the first-run tour of the app; null = not yet, so they are shown it.
alter table users add column onboarded_at timestamptz;
