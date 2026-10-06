# FitFam API

The Spring Boot backend for **FitFam**, a fitness training platform.

> **Status: early scaffold.** The only endpoint today is `GET /health`. There is no database, no authentication and no
> business logic yet. This README describes what exists now and, clearly marked, what is planned.

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
| Other starters | Validation, Actuator |
| Planned | Spring Data JPA / Hibernate on Supabase Postgres, springdoc-openapi, Google ID token verification |
| Planned hosting | Railway |

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

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| `GET` | `/health` | Simple liveness check | `200 {"status":"ok"}` |
| `GET` | `/actuator/health` | Spring Boot Actuator health (the only Actuator endpoint exposed by default) | `200 {"status":"UP", ...}` |

No other endpoints exist yet.

## Configuration

Configuration lives in `src/main/resources/application.properties`.

| Property | Default | Notes |
|----------|---------|-------|
| `spring.application.name` | `fitfam-api` | |
| `server.port` | `${PORT:8081}` | Reads the `PORT` env var if set (Railway injects it), otherwise **8081**. 8081 is used locally because 8080 is commonly taken (for example by IIS on some Windows machines). |

Override the port for one run:

```powershell
$env:PORT = "9000"; .\mvnw.cmd spring-boot:run
```

Anything secret (database URL and credentials, Google client ID configuration, signing keys, AI keys) must come from
environment variables, never from a committed file. See [Security and secrets](#security-and-secrets).

## Project structure

```
fitfam-api/
├── pom.xml                         Maven build (Java 21, Spring Boot 4.1.1)
├── mvnw, mvnw.cmd, .mvn/           Maven wrapper
├── src/
│   ├── main/
│   │   ├── java/com/fitfam/api/
│   │   │   ├── FitfamApiApplication.java    Spring Boot entry point
│   │   │   └── HealthController.java        GET /health
│   │   └── resources/
│   │       └── application.properties
│   └── test/java/com/fitfam/api/
│       ├── FitfamApiApplicationTests.java   Context loads
│       └── HealthControllerTests.java       /health returns {"status":"ok"} (MockMvc)
└── .gitignore                      Ignores build output, IDE files, env files, keys, logs
```

Base package: `com.fitfam.api`.

## Testing

```bash
./mvnw test          # Windows PowerShell: .\mvnw.cmd test
```

Current tests: the application context loads, and `/health` returns `200` with `{"status":"ok"}`.

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

**Port already in use.** Set a different `PORT` (see [Configuration](#configuration)), or stop the other process.

## Security and secrets

- **Never commit secrets.** `.gitignore` excludes `.env`, `.env.*`, key and certificate files (`*.pem`, `*.key`, `*.p12`,
  `*.pfx`, `*.jks`, `*.keystore`), `application-local*` / `application-secret*` Spring config files, and logs.
- `application.properties` is tracked: it must contain **no** secrets, only safe defaults and `${ENV_VAR}` references.
- In production, secrets live only in Railway environment variables. Locally, use a gitignored `.env`-style file or your
  shell environment.
- The database is reachable **only** from this service. The frontend never talks to the database and never uses a
  Supabase key.
- All AI provider calls will be made server-side here, so AI keys are never exposed to a client.

## Planned architecture

These are design decisions already made but **not yet implemented**:

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
- **OpenAPI:** springdoc-openapi will publish the spec; the web app's TypeScript client/types will be generated from it.
- **Account deletion:** an endpoint that cascades will be provided.
- **Business logic lives here only**, never in the frontends.

The data model is not designed yet.

## Roadmap

- [x] Spring Boot scaffold (Java 21, Maven)
- [x] `GET /health`
- [ ] Database connection and first entities
- [ ] Google ID token verification and restricted login
- [ ] Session cookie, `me` and `logout`
- [ ] springdoc-openapi and generated TypeScript client
- [ ] Core domain features
- [ ] Deployment to Railway
- [ ] AI features (server-side only)

## Git workflow

- Default branch: `main`.
- Commit and branch in this repo only (each FitFam repo is independent).
- Never commit secrets (see above).

## Related repositories

- [fitfam-web](https://github.com/yotamblu/fitfam-web): the Next.js PWA.
- [fitfam-ums](https://github.com/yotamblu/fitfam-ums): the internal User Management System.
