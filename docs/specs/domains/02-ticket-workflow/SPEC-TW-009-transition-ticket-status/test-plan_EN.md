# SPEC-TW-009 — TDD Test Plan

## 1. TDD Order

### Step 1 — Aggregate Unit Tests

Write failing tests for the five valid transitions, all invalid transitions, missing assignee, and waiting metadata rules.

### Step 2 — Authorization Tests

Test actor scope, support-team queue access, Requester denial, and Automation Agent service identity.

### Step 3 — Handler Tests

Test orchestration order, idempotent replay, error mapping, version checks, response mapping, and outbox mapping.

### Step 4 — Repository Integration Tests

Use PostgreSQL/Testcontainers to verify conditional updates, CHECK constraints, append-only history, metadata cleanup, and transaction rollback.

### Step 5 — API Contract Tests

Validate headers, payload constraints, Problem Details, `ETag`, and OpenAPI examples.

### Step 6 — Event Contract Tests

Serialize `ticket.status-changed.v1` and validate version, ordering, privacy, and waiting metadata rules against AsyncAPI/schema.

### Step 7 — Concurrency and E2E Tests

Race commands at one version, replay idempotency keys, inject persistence failures, and verify full timeline/audit/outbox trace.

## 2. Minimum Test Matrix

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| UT-01 | Domain | `ASSIGNED -> IN_PROGRESS` | status changed, version +1 |
| UT-02 | Domain | `IN_PROGRESS -> WAITING_FOR_USER` | requester wait metadata set |
| UT-03 | Domain | `IN_PROGRESS -> WAITING_FOR_APPROVAL` | approval reference set |
| UT-04 | Domain | `WAITING_FOR_USER -> IN_PROGRESS` | waiting metadata cleared |
| UT-05 | Domain | `WAITING_FOR_APPROVAL -> IN_PROGRESS` | waiting metadata cleared |
| UT-06 | Domain | `TRIAGED -> IN_PROGRESS` | `INVALID_STATUS_TRANSITION` |
| UT-07 | Domain | target `RESOLVED` | `INVALID_STATUS_TRANSITION` |
| UT-08 | Domain | no assignee | `TICKET_NOT_ASSIGNED` |
| AP-01 | Application | happy start work | all ports invoked once |
| AP-02 | Application | queue denied | `QUEUE_ACCESS_DENIED`, no writes |
| AP-03 | Application | missing scope | `FORBIDDEN`, no writes |
| AP-04 | Application | stale version | `VERSION_CONFLICT`, no writes |
| DB-01 | Integration | successful transition | ticket/history/audit/outbox committed |
| DB-02 | Integration | metadata constraints | invalid rows rejected |
| DB-03 | Integration | rollback on outbox failure | no partial writes |
| DB-04 | Integration | rollback on history failure | no partial writes |
| CC-01 | Concurrency | same version, two transitions | one success, one conflict |
| ID-01 | Idempotency | identical replay | original response, one side effect |
| ID-02 | Idempotency | same key, different body | `IDEMPOTENCY_KEY_REUSED` |
| API-01 | Contract | missing If-Match | `428 PRECONDITION_REQUIRED` |
| API-02 | Contract | invalid target status | `400 VALIDATION_ERROR` or `409` per mapping |
| EVT-01 | Contract | status-changed schema | valid |
| E2E-01 | E2E | assign -> start -> wait user -> resume | correct final state and trace |

## 3. Atomicity Assertions

After injected failure, assert unchanged:

- ticket status, waiting metadata, and version;
- status history count;
- timeline and audit count;
- outbox count;
- finalized idempotency response.

## 4. Security Assertions

- actor/tenant/body spoofing is ignored or rejected;
- Requesters cannot transition status;
- queue scope predicates use the ticket's triaged queue;
- errors do not leak details from other queues;
- logs omit Authorization headers, idempotency keys, and full reason text.

## 5. Event Assertions

- exact event type `ticket.status-changed.v1`;
- aggregate version equals stored ticket version;
- previous/new status matches history;
- waiting metadata matches target status;
- no secrets, raw claims, or full identity profile.

## 6. Observability Assertions

Verify counters/timers:

```text
ticket_status_transition_commands_total{transition,outcome}
ticket_status_transition_conflicts_total{type}
ticket_status_transition_duration_seconds{transition}
```

Metric labels exclude ticket IDs, user IDs, reasons, and idempotency keys.

## 7. Exit Criteria

- all tests deterministic and passing;
- no skipped authorization, rollback, concurrency, or contract tests;
- migration runs on a Phase 01-008 schema;
- OpenAPI and AsyncAPI pass validation;
- `SPEC-TW-008` reassign regression covers `IN_PROGRESS`;
- E2E proves traceability across ticket, history, timeline, audit, and outbox.
