# Ticket Workflow Service

## Service Purpose

`ticket-workflow-service` owns the Ticket lifecycle for OpsMind: creation, state
transitions, assignment, escalation, resolution, and the integration events that
drive and are driven by the Agent Runtime, Approval, Tool Gateway, and
Verification services.

## Current Phase

**Phase 01 — Create Ticket Vertical Slice.**

The service now implements the first complete business vertical slice:
`POST /api/v1/tickets`. An authenticated Employee can create a Ticket, which
commits the Ticket, its initial Resolution Cycle, its initial SLA Cycle,
initial Status History, a required Business Audit record, and a
`ticket.created.v1` Outbox record atomically in one PostgreSQL transaction,
protected by API idempotency. See
[Phase 01 plan](../../docs/implementation-plans/domains/02-ticket-workflow/phase-01-create-ticket_EN.md)
and [SPEC-TW-001](../../docs/specs/domains/02-ticket-workflow/SPEC-TW-001-create-ticket/spec_EN.md).

## Prerequisites

- Java 21 (Temurin recommended)
- Docker (for Testcontainers and local infrastructure)
- No local Maven installation required — use the bundled Maven Wrapper

## Run Locally

Start local infrastructure first (see below), then:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Run Tests

Fast unit / component tests only:

```bash
./mvnw test
```

## Run Integration Tests

Full verification, including Testcontainers-backed integration tests,
ArchUnit, and static analysis:

```bash
./mvnw verify
```

## Start Local Infrastructure

```bash
docker compose -f ../../infrastructure/docker-compose/local-platform.yml up -d
```

Copy `../../infrastructure/docker-compose/.env.example` to `.env` in the same
directory and adjust credentials before starting, if you need non-default
values. Never commit a real `.env` file.

## Build Docker Image

```bash
docker build -t opsmind/ticket-workflow-service:local .
```

## Configuration Variables

Used by the `local` profile (`application-local.yml`):

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ connection |
| `KEYCLOAK_ISSUER_URI` | OAuth2 resource server JWT issuer |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry OTLP collector endpoint |
| `TICKET_REQUESTER_PSEUDONYMIZATION_SECRET` | HMAC key used to pseudonymize the requester id in `ticket.created.v1` |

Tests use `application-test.yml` and Testcontainers-provided connection
properties; no fixed ports or hosts are required.

## Create a Ticket (curl example)

Requires a valid Employee JWT with the `tickets:create` scope (from Keycloak
in a real environment). `Idempotency-Key` is required and must be a stable,
client-generated value (1-128 characters) — reusing it with the same payload
safely replays the original result instead of creating a duplicate Ticket.

```bash
curl -i -X POST http://localhost:8080/api/v1/tickets \
  -H "Authorization: Bearer $EMPLOYEE_JWT" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
        "title": "Cannot sign in to Housing Portal",
        "description": "Duo keeps asking me to enroll again.",
        "applicationCode": "HOUSING_PORTAL",
        "source": "PORTAL"
      }'
```

A successful call returns:

```http
HTTP/1.1 201 Created
Location: /api/v1/tickets/{ticketId}
ETag: "0"
Content-Type: application/json

{
  "ticketId": "018f0f1e-7b31-7a00-8f42-31f9b25b1a91",
  "displayId": "INC-2048",
  "status": "NEW",
  "createdAt": "2026-07-23T16:30:00Z",
  "version": 0
}
```

## Current Non-goals

Phase 01 explicitly does not deliver:

- Ticket query or list endpoints
- Add message
- Triage, classification, or Agent workflow
- Waiting for user, approval, or tool execution
- Resolve, close, reopen, cancel, assign, or escalate
- RabbitMQ consumer set (business event consumption)
- Reconciliation workflow
- Full Keycloak realm hardening
- Enforced rate limiting (reserved for Phase 09)

## Design Links

- [Phase 01 — Create Ticket Vertical Slice](../../docs/implementation-plans/domains/02-ticket-workflow/phase-01-create-ticket_EN.md)
- [SPEC-TW-001 — Create Ticket](../../docs/specs/domains/02-ticket-workflow/SPEC-TW-001-create-ticket/spec_EN.md)
- [13 — Package and Class Design](../../docs/low-level-design/domains/02-ticket-workflow/13-package-and-class-design/README_CN.md)
- [14 — Testing Strategy](../../docs/low-level-design/domains/02-ticket-workflow/14-testing-strategy/README_CN.md)
- [Traceability Matrix](../../docs/traceability/02-ticket-workflow/traceability-matrix.yaml)

## Next Phase

**Phase 02 — Ticket Query and Message Slice.** Requires this phase's exit
criteria to be fully met first.
