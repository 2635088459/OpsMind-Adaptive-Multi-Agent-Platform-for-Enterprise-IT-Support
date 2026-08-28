# User Access And Authentication Service

## Service Purpose

`user-access-authentication-service` (domain 01) is OpsMind's identity and
authorization security boundary. External Keycloak is the sole source of
truth for credentials, OIDC/OAuth2, and MFA; this service owns trusted
identity mapping, role assignments, session/revocation metadata, step-up
evidence, workload identity, and authorization decisions — never passwords,
MFA secrets, raw tokens, or IdP private keys. Authorization denies by
default.

## Status

All 36 specs across all 10 implementation phases are complete
(`SPEC-UA-001` – `SPEC-UA-036`; see
[Traceability entries](../../docs/specs/domains/01-user-access-authentication/) —
every `SPEC-UA-0xx/traceability-entry.yaml` is `status: implemented`). The
service is release-ready per its own 14-testing-strategy checklist
([SPEC-UA-036](../../docs/specs/domains/01-user-access-authentication/SPEC-UA-036-final-coverage-audit-release-readiness/)).

| Phase | Specs | Theme |
|---|---|---|
| 00 — Engineering Foundation | UA-001~003 | Hexagonal skeleton, real Postgres schema, transactional outbox/audit |
| 01 — OIDC And Token Trust | UA-004~007 | Discovery, real browser Authorization Code+PKCE login, JWT/JWKS validation, claims normalization |
| 02 — User And Session Lifecycle | UA-008~010 | Profile provisioning/linking, session refresh/logout/revocation, workload identity |
| 03 — Authorization RBAC Scope | UA-011~015 | Role/permission catalog, grant lifecycle+delegation limits, tenant/queue scope, decision API, SELF ownership |
| 04 — Authentication Assurance Step-Up | UA-016~019 | Assurance-level computation, step-up lifecycle, real Keycloak MFA proof, break-glass |
| 05 — Experience Access Contracts | UA-020~024 | Real signed-JWT-over-HTTP E2E for Employee/Ticket/Support/Approver personas + 01↔06 human-actor contract |
| 06 — Cross-Domain Identity Contracts | UA-025~028 | Workload-identity contracts (Policy Governance, Ticket Workflow, Runtime/Tool/Memory), real RabbitMQ approval-outcome consumer |
| 07 — Security Observability Privacy | UA-029~031 | Security alert events, real metrics/traces, audit hash-chain + PII retention |
| 08 — Failure Recovery Degraded Mode | UA-032~034 | JWKS max-stale outage tolerance, session-revocation reconciliation, token-replay + JWKS-poisoning defense |
| 09 — Final Verification Release | UA-035~036 | Shared cross-domain contract-test harness, this coverage audit |

## Prerequisites

- Java 21
- Maven (via the bundled `./mvnw` wrapper — no local Maven install needed)
- Docker (for `testcontainers`-backed integration tests, and for local infrastructure)

## Run Locally

```bash
./mvnw spring-boot:run
```

Serves on `http://localhost:8087` (`SERVER_PORT`). `GET /actuator/health`
returns liveness/readiness probes; `GET /actuator/info` and
`GET /actuator/prometheus` are also exposed.

Requires a reachable Postgres instance (shares the `ticket_workflow`
database with the other Java services, owning its own `identity` schema —
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`) with Flyway
migrations applied automatically on boot, a reachable RabbitMQ broker
(`RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD`),
and a reachable Keycloak realm (`KEYCLOAK_ISSUER_URI`, default
`http://localhost:8081/realms/opsmind`) — see **Start Local
Infrastructure** below.

## Run Tests

Fast unit/domain/application/ArchUnit tests (no Docker required):

```bash
./mvnw test
```

Integration tests (requires Docker — each `*IT` class spins up its own
fresh Testcontainers Postgres/RabbitMQ fork, `reuseForks=false`, never
touches a shared/persistent instance):

```bash
./mvnw failsafe:integration-test
```

Full suite (the real release gate):

```bash
./mvnw clean verify
```

258 tests total as of SPEC-UA-034 (unit/domain/application/ArchUnit +
Testcontainers-IT), all green; ArchUnit's `LayerDependencyTest` (7 rules)
enforces the hexagonal package boundaries on every run.

## Database Migrations

Flyway runs automatically against the `identity` schema on application
startup (`V001` – `V014` as of SPEC-UA-034; see
[07-data-model](../../docs/low-level-design/domains/01-user-access-authentication/07-data-model/README_EN.md)).
No down-migrations are authored anywhere in this codebase — this project's
own rollback convention (shared with its sibling Java services) is
forward-only: a bad migration is corrected by a new, later migration, never
by reverting one already applied to a running environment. Restoring from a
database backup is the only rollback path for a migration that must be
undone entirely.

## Configuration Variables

| Variable | Purpose | Default |
|---|---|---|
| `SERVER_PORT` | HTTP port | `8087` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Shared PostgreSQL connection | `localhost:5432/ticket_workflow` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ connection (real outbox publisher + approval-decision consumer) | `localhost:5672` / `guest` |
| `KEYCLOAK_ISSUER_URI` | OIDC issuer for both the resource-server decoder and the browser-login client registrations | `http://localhost:8081/realms/opsmind` |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | Browser login (`opsmind`) and step-up (`opsmind-stepup`) OAuth2 client credentials | `user-access-authentication-service` / *(empty)* |
| `IDENTITY_DEFAULT_TENANT_ID` | Fixed, server-side tenant id (no per-tenant claim convention exists yet — see `BrowserLoginProperties`) | `opsmind` |
| `IDENTITY_LOGIN_SUCCESS_REDIRECT_URI`, `IDENTITY_LOGIN_FAILURE_REDIRECT_URI` | Post-login browser redirect targets | `/` / `/login?error` |
| `IDENTITY_STEP_UP_SUCCESS_REDIRECT_URI`, `IDENTITY_STEP_UP_FAILURE_REDIRECT_URI` | Post-step-up browser redirect targets | `/` / `/step-up?error` |
| `OIDC_ALLOWED_ALGORITHMS` | JWS signature algorithm allow-list | `RS256` |
| `OIDC_CLOCK_SKEW` | JWT timestamp validation tolerance (capped at 2 minutes regardless of this value) | `PT60S` |
| `OIDC_JWKS_MAX_STALE` | How long an already-cached JWKS key set keeps validating tokens during a live JWKS-endpoint outage (capped at 2 hours) | `PT30M` |
| `KEYCLOAK_ADMIN_CLIENT_ID`, `KEYCLOAK_ADMIN_CLIENT_SECRET` | Opt-in Keycloak Admin API client-credentials (real end-session notification on revoke); unset means a graceful no-op, never an endlessly-retried failure | *(empty)* |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry OTLP collector endpoint (metrics + traces) | `http://localhost:4318` |

## Start Local Infrastructure

```bash
docker compose -f ../../infrastructure/docker-compose/local-platform.yml up -d
```

Copy `../../infrastructure/docker-compose/.env.example` to `.env` in the
same directory and adjust credentials before starting, if you need
non-default values. Never commit a real `.env` file.

## API Surface

All routes are internal-only (`/internal/identity/v1/...`), never exposed
to end users directly; the real browser-facing surface is only the OAuth2
redirect/callback endpoints (`/oauth2/**`, `/login/**`) Spring Security's
own filter chain owns.

- **Identity/session/authorization** (`UserIdentityController`,
  `SessionController`, `AuthorizationDecisionController`,
  `TokenIntrospectionController`): `GET /users/me`, `POST /sessions`,
  `POST /sessions/{id}/revoke`, `POST /sessions/logout`,
  `POST /sessions/{id}/refresh`, `POST /authorization-decisions`,
  `POST /tokens/introspect-context`.
- **Step-up and break-glass** (`StepUpChallengeController`,
  `BreakGlassController`): `POST /step-up/challenges`,
  `POST /step-up/challenges/{id}/verify`,
  `POST /step-up/challenges/{id}/consume`,
  `POST /step-up/challenges/{id}/cancel`, `POST /break-glass/activate`,
  `POST /break-glass/{id}/revoke`.
- **Admin** (`identity:role:grant`/`identity:role:revoke`/`identity:user:admin`
  RBAC-gated, per SPEC-UA-011): role-assignment grant/revoke/cancel,
  service-identity register/disable/get/validate, user-identity
  status-change/profile-sync, audit-record query
  (`GET /admin/audit-records?correlationId=...`).
- **Reconciliation** (`ReconciliationController`, `OutboxAdminController` —
  admin/scheduler-triggered, nothing runs these automatically): role
  assignments, sessions (expiry, end-session notifications, inactive
  identities), step-up challenges, service identities, break-glass grants,
  privacy-retention PII redaction, outbox dispatch.

See [05-api-contracts](../../docs/low-level-design/domains/01-user-access-authentication/05-api-contracts/README_EN.md)
for the full contract and [06-event-contracts](../../docs/low-level-design/domains/01-user-access-authentication/06-event-contracts/README_EN.md)
for every consumed/published event shape.

## Architecture

Hexagonal (ports and adapters): `domain` → `application` → `infrastructure`
/ `api`, enforced by `architecture.LayerDependencyTest` (7 ArchUnit rules,
including that no class anywhere depends on another domain's own service
package). See
[13-package-and-class-design](../../docs/low-level-design/domains/01-user-access-authentication/13-package-and-class-design/README_EN.md).

## Residual Risks And Deliberate Exclusions

Compiled from every SPEC-UA-0xx spec's own "deliberately did not build"
decision — real, honestly-scoped gaps, not oversights:

- **No rate-limiting anywhere** (11-security names it for login/callback/
  introspection/step-up/admin APIs) — no rate-limiting mechanism exists in
  this codebase at all, and no single spec in this domain's roadmap owns
  building one (SPEC-UA-034).
- **`UserSession#markCompromised`/`COMPROMISED` and `#terminate`/`TERMINATED`
  stay unwired** — their real trigger (a Keycloak-originated "credential
  compromise"/admin event, or an undefined "normal termination" signal) has
  no real producer anywhere in this monorepo (SPEC-UA-033).
- **No formal `NORMAL`/`READ_ONLY_IDENTITY`/`CACHED_VALIDATION_ONLY`/
  `FAIL_CLOSED` degraded-mode state machine** — only the two concrete rows
  this domain's own LLD names (Keycloak-unavailable sensitive-action
  fail-closed, JWKS max-stale fallback) are real; `READ_ONLY_IDENTITY` has
  no textual anchor anywhere (SPEC-UA-032).
- **PII protection is redaction-on-retention, not field-level encryption** —
  07-data-model says email/display name "may be encrypted," but names no
  concrete KMS/encryption mechanism; redaction was the real, groundable
  half built (SPEC-UA-031).
- **No Keycloak Testcontainers module anywhere** — every real OIDC/JWKS/MFA
  behavior in this test suite is proven against a JDK-only `StubHttpServer`
  serving real discovery/JWKS/signed tokens instead (no official Keycloak
  Testcontainers module exists; this is the established, deliberate
  substitute across the entire domain, not a shortcut).
- **JWT `typ` header is not validated** — cannot be verified correctly
  without a real Keycloak instance to confirm the actual value it sends
  (SPEC-UA-004/006).

## Runbooks

Nothing in this service schedules its own reconciliation — every scan
below is admin/scheduler-triggered only (mirrors the platform-wide
"no in-process scheduler for time-driven transitions" convention):

| Endpoint | When to call it |
|---|---|
| `POST /admin/role-assignments/reconcile` | Activate due `PENDING` grants / expire past-`validUntil` `ACTIVE` ones |
| `POST /admin/sessions/reconcile` | Expire `ACTIVE` sessions past their own `expiresAt` |
| `POST /admin/sessions/reconcile-end-session-notifications` | Retry best-effort Keycloak end-session calls for `REVOKED`-but-unnotified sessions |
| `POST /admin/sessions/reconcile-inactive-identities` | Revoke `ACTIVE` sessions whose owning identity has since gone `DISABLED`/`DEPROVISIONED` |
| `POST /admin/step-up/reconcile` | Expire `PENDING` step-up challenges past their own timeout |
| `POST /admin/service-identities/reconcile` | Retire `ACTIVE` workload identities past their own `validUntil` |
| `POST /admin/break-glass/reconcile` | Expire `ACTIVE` break-glass grants past their own bounded TTL |
| `POST /admin/user-identities/reconcile-privacy-retention` | Redact PII for `DEPROVISIONED` identities past the retention window |
| `POST /admin/outbox/dispatch` | Drain `PENDING` outbox rows to RabbitMQ |

Recovery from an IdP/JWKS outage needs no manual runbook step — the
outage-tolerant JWKS source (SPEC-UA-032) recovers automatically the next
time a real fetch succeeds.

## Release Sign-Off

`./mvnw clean verify` is green (unit/domain/application/ArchUnit +
Testcontainers-IT, 0 failures/errors) as of SPEC-UA-034, the real
migrations run cleanly from empty on every IT fork, and every mandatory
security case 14-testing-strategy names has a real, passing proof
somewhere in this suite (wrong issuer/audience/signature/alg/kid,
expired/nbf/skew, token substitution/replay, cross-tenant/horizontal/
vertical escalation, step-up action/resource mismatch and double
consumption, JWKS poisoning). This service is release-ready.

## Design Links

- [Implementation plans (phase-00 – phase-09)](../../docs/implementation-plans/domains/01-user-access-authentication/)
- [Specs (SPEC-UA-001 – SPEC-UA-036)](../../docs/specs/domains/01-user-access-authentication/)
- [Low-level design (14 aspects)](../../docs/low-level-design/domains/01-user-access-authentication/)
