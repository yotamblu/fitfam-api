# FitFam API

The Spring Boot backend for **FitFam**, a fitness training platform.

> **Status: early development.** Health check, Google-based login and a few admin endpoints exist. This README describes
> what exists now and, clearly marked, what is planned.

---

## Table of contents

1. [Where this fits](#where-this-fits)
2. [Tech stack](#tech-stack)
3. [Prerequisites](#prerequisites)
4. [Quick start](#quick-start)
5. [Endpoints](#endpoints)
6. [Configuration](#configuration)
7. [Project structure](#project-structure)
8. [Testing](#testing)
9. [Windows troubleshooting](#windows-troubleshooting)
10. [Security and secrets](#security-and-secrets)
11. [Planned architecture](#planned-architecture)
12. [Roadmap](#roadmap)
13. [Git workflow](#git-workflow)
14. [Related repositories](#related-repositories)

---

## Where this fits

FitFam is made of independent repositories:

| Repo | Role |
|------|------|
| **fitfam-api** (this repo) | Spring Boot backend. The only component that will talk to the database. |
| [fitfam-web](https://github.com/yotamblu/fitfam-web) | Next.js installable PWA, the app clients use. A pure client of this API. |
| [fitfam-ums](https://github.com/yotamblu/fitfam-ums) | Internal admin site (not started beyond a scaffold). |

```
Postgres  ->  Spring Boot (this repo, the ONLY thing that talks to the DB)  ->  Next.js (pure client of the API)
```

## Tech stack

| Area | Choice |
|------|--------|
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 |
| Build | Maven (wrapper included, no global Maven needed) |
| Web | Spring Web MVC |
| Other | Validation, Actuator, Spring Security (stateless), Spring Data JPA, Flyway, PostgreSQL, google-api-client, jjwt |
| Planned | springdoc-openapi |
| Hosting | Render (free plan, Docker), see [Deploying to Render](#deploying-to-render-free-plan) |

## Prerequisites

- **JDK 21** (the project targets Java 21; for example [Eclipse Temurin 21](https://adoptium.net/)).
- Nothing else. Use the included Maven wrapper (`mvnw` / `mvnw.cmd`).

> If you only have JDK 17 installed, you can still run the app by adding `-Djava.version=17` to the Maven command. The
> code does not currently use anything newer than Java 17, but the supported target is 21.

## Quick start

```bash
git clone https://github.com/yotamblu/fitfam-api.git
cd fitfam-api
```

**Windows (PowerShell):**

```powershell
.\mvnw.cmd spring-boot:run
```

**macOS / Linux / Git Bash:**

```bash
./mvnw spring-boot:run
```

(With JDK 17 only: add `-Djava.version=17`.)

Wait for the log line `Started FitfamApiApplication`, then check:

```bash
curl http://localhost:8081/health
# {"status":"ok"}
```

Stop the app with **Ctrl+C**.

## Endpoints

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `GET` | `/health` | public | Liveness check, returns `{"status":"ok"}` |
| `GET` | `/actuator/health` | public | Spring Boot Actuator health |
| `POST` | `/auth/google` | public | Body `{"credential": "<Google ID token>"}`. Verifies the token and, if the email was pre-approved, sets the session cookie |
| `GET` | `/auth/me` | logged in | The current user |
| `POST` | `/auth/logout` | public | Clears the session cookie |
| `GET` | `/admin/plans` | admin | Available plans and their levels |
| `GET` | `/admin/users` | admin | Users with their enrollments |
| `POST` | `/admin/users` | admin | Pre-approve a user by email and enroll them in plans |
| `GET` | `/admin/waitlist` | admin | Read-only, paginated list of waitlist signups (newest first) with a flag for emails that already have an account. Query params: `page`, `size`, `q` (search in the email), `status=waiting` (only those without an account), `sport`. The response also carries a `summary` with whole-list totals (signed up, already users, count per sport) that ignores the filters |

Errors are returned as `{"error": "<code>"}` (for example `invalid_token`, `not_invited`, `email_exists`).

## Configuration

Configuration lives in `src/main/resources/application.properties`; it contains no secrets, only `${ENV_VAR}`
references. Locally the values are read from a gitignored `.env` file in the project root (`KEY=value` lines); in
production they are real environment variables.

| Variable | Required | Purpose |
|----------|----------|---------|
| `DB_URL` | yes | JDBC URL of the Postgres database |
| `DB_USER` | yes | Database user |
| `DB_PASSWORD` | yes | Database password |
| `GOOGLE_CLIENT_ID` | yes | Google OAuth client ID; ID tokens must be issued for it |
| `JWT_SECRET` | yes | Key used to sign session tokens (at least 32 bytes, random) |
| `COOKIE_SECURE` | no (default `true`) | Set `false` only for local http development |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated web origins allowed to call the API with cookies (default: `http://localhost:3000,http://localhost:3001`) |
| `PORT` | no (default `8081`) | HTTP port (8080 is commonly taken, e.g. by IIS on Windows) |

Example `.env` (never commit it):

```
DB_URL=jdbc:postgresql://<host>:5432/postgres
DB_USER=<user>
DB_PASSWORD=<password>
GOOGLE_CLIENT_ID=<client id>
JWT_SECRET=<long random string>
COOKIE_SECURE=false
```

Override the port for one run:

```powershell
$env:PORT = "9000"; .\mvnw.cmd spring-boot:run
```

## Deploying to Render (free plan)

Render builds straight from this GitHub repo, so deploying is: connect the repo, enter a few settings, and every push
to `main` redeploys. Render has no native Java runtime, so it builds the `Dockerfile` in this repo; `render.yaml` (a
"Blueprint") describes the service so nothing has to be configured by hand.

1. In Render choose **New -> Blueprint**, connect this GitHub repository, and accept the `fitfam-api` service from
   `render.yaml` (free plan, Frankfurt, health check `/health`).
2. Render asks for the values marked `sync: false`. All of them are required:

   | Variable | Value |
   |----------|-------|
   | `DB_URL` | JDBC URL of the Postgres database (session pooler) |
   | `DB_USER` | Database user |
   | `DB_PASSWORD` | Database password |
   | `GOOGLE_CLIENT_ID` | Google OAuth client ID |
   | `CORS_ALLOWED_ORIGINS` | The **https** origin(s) of any site that calls the API from a browser, comma separated. **It must not contain localhost or http**: with secure cookies on, the API refuses to start otherwise (you will see "Refusing to start" in the logs) |

   `JWT_SECRET` is generated by Render, and `COOKIE_SECURE=true` plus the JVM memory flags are already in `render.yaml`.
3. After the first deploy, `https://<service>.onrender.com/health` should answer `{"status":"ok"}`. The database
   migrations run automatically at startup.

Things to know about the free plan:

- A free service **sleeps after 15 minutes without traffic** and takes about a minute to wake up (the first request
  after a sleep is slow). It gets 512 MB of memory, a small slice of one CPU, and 750 free hours a month per workspace.
  Render says free instances are not meant for production.
- Startup is slower on that small CPU than on a laptop (about 13 seconds locally). The JVM flags in `render.yaml` keep
  memory well under the 512 MB limit (about 290 MB measured locally).
- A browser can only keep the login cookie if the site and the API are on the same site (same registrable domain), or
  if the site forwards API calls from its own server (as the customer web app does with its `/backend` proxy). A site
  that calls `*.onrender.com` straight from the browser from another domain will not stay logged in.
- Secrets live only in Render's environment settings; the image never contains `.env` files (see `.dockerignore`).

## Project structure

```
fitfam-api/
├── pom.xml                         Maven build (Java 21, Spring Boot 4.1.1)
├── mvnw, mvnw.cmd, .mvn/           Maven wrapper
├── src/main/
│   ├── java/com/fitfam/api/
│   │   ├── FitfamApiApplication.java    Entry point
│   │   ├── HealthController.java        GET /health
│   │   ├── config/                      AppProperties, SecurityConfig (stateless auth, CORS)
│   │   ├── auth/                        Google token verification, session token + cookie, login endpoints
│   │   ├── admin/                       Admin endpoints and services, audit log, waitlist reader
│   │   ├── domain/                      JPA entities and repositories
│   │   └── web/                         Error handling (ApiException)
│   └── resources/
│       ├── application.properties
│       └── db/migration/V1__init.sql    Database schema (Flyway)
├── src/test/                       Unit and web-layer tests (no real database or Google needed)
└── .gitignore                      Ignores build output, IDE files, env files, keys, logs
```

Base package: `com.fitfam.api`.

## Database

The schema lives in `src/main/resources/db/migration/` (Flyway). The first version was applied by hand, so Flyway is
configured to baseline at version 1; later changes are added as `V2__...sql`, `V3__...sql` and so on. Hibernate runs in
`validate` mode, so the app refuses to start if the entities and the tables disagree. Row Level Security is enabled on
every table with no policies: only this service's database connection can read or write.

## Testing

```bash
./mvnw test          # Windows PowerShell: .\mvnw.cmd test
```

Tests cover session tokens, the login rules (pre-approved email only, verified email required), admin authorization, adding
users with plans, CORS and the cookie flags. They use mocks and never touch a real database or Google.

## Windows troubleshooting

**`PKIX path building failed` when Maven downloads dependencies.**
The JDK's own certificate store does not trust Maven Central on some Windows machines (for example behind antivirus
HTTPS inspection). Tell Java to use the Windows certificate store for that run:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NUL"
.\mvnw.cmd spring-boot:run
```

You only need this when Maven has to **download** something (first build, or after adding a dependency). Once
dependencies are cached in `~/.m2`, normal runs do not need it.

**Login returns 503 (`google_unavailable`) on Windows.**
The API could not fetch Google's public keys because the JDK's truststore rejects Google's certificate (same cause as
above). The `windows-truststore` Maven profile in `pom.xml` makes `spring-boot:run` use the Windows certificate store
automatically on Windows. If you run the jar yourself on Windows, add
`-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NUL`. Linux (including production) needs
nothing.

**Port already in use.** Set a different `PORT` (see [Configuration](#configuration)), or stop the other process.

## Security and secrets

- **Never commit secrets.** `.gitignore` excludes `.env`, `.env.*`, key and certificate files (`*.pem`, `*.key`, `*.p12`,
  `*.pfx`, `*.jks`, `*.keystore`), `application-local*` / `application-secret*` Spring config files, and logs.
- `application.properties` is tracked: it must contain **no** secrets, only safe defaults and `${ENV_VAR}` references.
- In production, secrets live only in the host's environment variables. Locally, use a gitignored `.env` file.
- **Access control:** everything except the health check, login and logout needs a valid session; `/admin/**` needs the
  admin role. The user and role are re-read from the database on every request, so removing or demoting someone applies
  at once. Forged, unsigned, tampered, wrongly signed and expired tokens are rejected (unit tests plus live checks).
- **Sessions:** a signed token in an `HttpOnly`, `SameSite=Lax` cookie. Customers get 7 days, admins 12 hours
  (`app.session.admin-hours`). Sessions are stateless, so there is no server-side "log out everywhere".
- **Browser-facing headers** on every response: CSP `default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`,
  `nosniff`, `Referrer-Policy: no-referrer`, HSTS, `no-store`. Only the health check is exposed by Actuator, and errors
  never include messages or stack traces.
- **CORS** uses an allowlist. The app refuses to start if `COOKIE_SECURE` is on (a real deployment) while
  `CORS_ALLOWED_ORIGINS` still contains localhost or a plain-http origin.
- **Database:** only this service (the database owner) reads or writes our tables. Row Level Security is on for every
  table with no policies, and `V2__lock_down_public_roles.sql` removes all table/sequence/function access from the
  database host's public-facing roles (`anon`, `authenticated`), including for tables created later. On a new database,
  also run `alter table public.flyway_schema_history enable row level security;` once while the API is stopped (Flyway
  cannot alter its own history table while it runs).
- **Input safety:** all SQL uses bound parameters; the only dynamic SQL is assembled from fixed fragments (the waitlist
  filters), and a test checks that hostile input is never concatenated.
- All AI provider calls (when added) are made server-side here, so AI keys are never exposed to a client.

## Architecture and decisions

Design decisions. Items marked *(planned)* are not implemented yet:

- **Auth: Google Sign-In only, permanently.** No other providers, no email/password, no Supabase Auth.
  - The web app obtains a Google ID token and sends it to the API.
  - The API verifies it: signature via Google's JWKS, plus `aud` (our OAuth client ID), `iss` and `exp`, and trusts the
    email only when `email_verified` is `true`.
  - Access is restricted to pre-approved emails. Google is used purely to prove the user owns that email; no Google
    access or refresh tokens are stored.
  - After verification the API issues its own session as an `HttpOnly`, `Secure`, `SameSite` cookie. Tokens are never
    put in `localStorage`.
  - Web and API will share a parent domain (as subdomains) so cookies work.
- **Database:** Supabase Postgres, accessed only from this service via JDBC and Spring Data JPA / Hibernate. The schema
  will be expressed as JPA entities. Row Level Security enabled on every table with no public policies.
- **OpenAPI** *(planned)*: springdoc-openapi will publish the spec; the web app's TypeScript client/types will be generated from it.
- **Account deletion** *(planned)*: an endpoint that cascades will be provided.
- **Business logic lives here only**, never in the frontends.

The data model is not designed yet.

## Roadmap

- [x] Spring Boot scaffold (Java 21, Maven)
- [x] `GET /health`
- [x] Database connection (Postgres, JPA, Flyway)
- [x] Google ID token verification and restricted login
- [x] Session cookie, `me` and `logout`
- [x] Admin endpoints to pre-approve users and enroll them in plans
- [ ] springdoc-openapi and generated TypeScript client
- [ ] Account deletion
- [ ] Core domain features
- [x] Deployment files for Render (`Dockerfile`, `render.yaml`)
- [ ] First deploy to Render
- [ ] AI features (server-side only)

## Git workflow

- Default branch: `main`.
- Commit and branch in this repo only (each FitFam repo is independent).
- Never commit secrets (see above).

## Related repositories

- [fitfam-web](https://github.com/yotamblu/fitfam-web): the Next.js PWA.
- [fitfam-ums](https://github.com/yotamblu/fitfam-ums): the internal User Management System.
