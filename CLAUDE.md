# fitfam-api

Spring Boot backend for FitFam. Java 21, Spring Boot 4.1.1, Maven (wrapper included). Base package `com.fitfam.api`.

This repo is PUBLIC. Never commit secrets, and keep code, tests, README and commit messages free of business details
(real plan names, level names, pricing, strategy, customer data). Use neutral placeholders in tests. Business context
may exist in a private `../CLAUDE.md` outside this repo; never copy it here.

## Architecture rules
- This service is the ONLY thing that talks to the database. Frontends are pure clients of this API.
- All business logic lives here. Any AI provider call must be made here, server-side.
- Postgres (Supabase) via Spring Data JPA; Hibernate runs in `validate` mode. Schema changes are Flyway migrations in
  `src/main/resources/db/migration/` (`V1__init.sql` was applied by hand, Flyway baselines at 1; add `V2__...sql`, never
  edit an applied migration). Keep migrations structure-only (no business seed data in git).
- Row Level Security is on for every table with no policies. Every table references `users.id`.

## Auth (Google only, invite-only)
- `POST /auth/google` verifies the Google ID token (`google-api-client`: signature, `aud`, `iss`, `exp`) and requires
  `email_verified`. Google only proves the email. It is accepted only if that email already exists in `users`
  (otherwise 403 `not_invited`, nothing created). No `google_sub`, no Google tokens stored.
- Session: own HS256 JWT (user id only) in an HttpOnly `ff_session` cookie, SameSite=Lax, 7 days. `SessionAuthFilter`
  reloads the user from the DB on every request. Never put tokens in localStorage or responses.
- `/admin/**` requires `role = 'admin'`. CSRF protection relies on SameSite=Lax + the CORS allowlist + JSON-only
  bodies; keep state-changing endpoints POST/PUT/DELETE with JSON.
- Errors are `{"error": "<code>"}` via `ApiException` / `ApiExceptionHandler`.
- `GET /admin/waitlist` (admin only) reads `waitlist_signups`, a table written by a separate public waitlist site in
  the same database. `WaitlistService` only ever SELECTs, with plain SQL via `JdbcTemplate` (not a JPA entity), so a
  change to that table cannot stop the API from starting; it returns 503 `waitlist_unavailable` if the table is gone.
  Never expose its `ip_hash` or `user_agent` columns. Page size is clamped to 200. Search/status/sport filters are built
  from fixed SQL fragments with bound `?` parameters only (search uses `position()`, so `%` and `_` are not wildcards);
  keep it that way. The response includes a `summary` (whole-list totals) next to the filtered page.

## Config (env vars; locally from the gitignored `.env`)
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `GOOGLE_CLIENT_ID`, `JWT_SECRET` (>= 32 bytes), `COOKIE_SECURE` (`false` for local
http), `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000,http://localhost:3001`), `PORT` (default 8081).
`application.properties` holds only `${ENV}` references. `.gitignore` covers `.env*`, key files, `application-local*`,
logs and `/db-local/`.

## Commands
- Run: `./mvnw -Djava.version=17 spring-boot:run` (PowerShell: `.\mvnw.cmd ...`); check `curl localhost:8081/health`.
  Drop `-Djava.version=17` once JDK 21 is installed (this machine has only JDK 17; the code needs nothing newer).
- Test: `./mvnw -Djava.version=17 test` (43 tests; mocks only, no real DB or Google needed).

## Windows gotchas
- `PKIX path building failed` while Maven downloads: run Maven with
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NUL"`. Only needed when new
  dependencies must be downloaded.
- The same problem broke fetching Google's keys at runtime (login returned 503 `google_unavailable`). The
  `windows-truststore` profile in `pom.xml` fixes it for `spring-boot:run` on Windows; Linux/production is unaffected.
- Port 8080 is taken by IIS on the dev machine, hence the default 8081.

## Security rules (audited 2026-10-07)
- Keep every new route under authentication by default (`anyRequest().authenticated()`); put admin-only routes under
  `/admin/**`. Never expose more Actuator endpoints than `health`. Never return exception messages to clients.
- Admin sessions are short (`app.session.admin-hours`, 12) because tokens cannot be revoked server-side; the user and
  role are reloaded per request, so deleting or demoting a user is an instant lockout.
- Keep the response headers in `SecurityConfig` (CSP, frame, referrer, HSTS). `requireSafeCorsForProduction` makes the
  app refuse to start with secure cookies plus a non-https/localhost CORS origin; set `CORS_ALLOWED_ORIGINS` in prod.
- Migrations must keep `anon`/`authenticated` without any access (V2). New tables get no grants for them by default
  (default privileges are revoked). Do not alter `flyway_schema_history` inside a migration (it deadlocks on Flyway's
  own lock): enable its RLS manually on a new database.
- All SQL via bound parameters. No string-concatenated user input, ever.

## Before pushing
Scan the commits for real secret values and business details. Do not push without being asked.
