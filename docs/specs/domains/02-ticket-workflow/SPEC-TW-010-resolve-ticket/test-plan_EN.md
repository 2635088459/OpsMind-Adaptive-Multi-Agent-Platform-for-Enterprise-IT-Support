# SPEC-TW-010 — TDD Test Plan

## 1. TDD Order

### Step 1 — Aggregate Unit Tests

Write failing tests for `IN_PROGRESS -> RESOLVED`, invalid states, missing assignee, invalid resolution code, and summary rules.

### Step 2 — Resolution Cycle Tests

Test that the current cycle exists, is incomplete, belongs to the same ticket, and is completed on successful resolve.

### Step 3 — Authorization Tests

Test actor scope, support-team queue access, Requester denial, and Automation Agent service identity.

### Step 4 — Handler Tests

Test orchestration order, idempotent replay, error mapping, version checks, response mapping, and outbox mapping.

### Step 5 — Repository Integration Tests

Use PostgreSQL/Testcontainers to verify conditional updates, CHECK constraints, cycle completion, append-only history, metadata cleanup, and transaction rollback.

### Step 6 — API Contract Tests

Validate headers, payload constraints, Problem Details, `ETag`, and OpenAPI examples.

### Step 7 — Event Contract Tests

Serialize `ticket.resolved.v1` and validate version, ordering, and privacy rules against AsyncAPI/schema.

### Step 8 — Concurrency and E2E Tests

Race commands at one version, replay idempotency keys, inject persistence failures, and verify full timeline/audit/outbox trace.

## 2. Minimum Test Matrix

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| UT-01 | Domain | `IN_PROGRESS -> RESOLVED` | status changed, version +1 |
| UT-02 | Domain | `ASSIGNED -> RESOLVED` | `INVALID_STATUS_TRANSITION` |
| UT-03 | Domain | no assignee | `TICKET_NOT_ASSIGNED` |
| UT-04 | Domain | invalid resolution code | rejected |
| UT-05 | Domain | blank/short summary | rejected |
| AP-01 | Application | happy resolve | all ports invoked once |
| AP-02 | Application | missing scope | `FORBIDDEN`, no writes |
| AP-03 | Application | queue denied | `QUEUE_ACCESS_DENIED`, no writes |
| AP-04 | Application | stale version | `VERSION_CONFLICT`, no writes |
| AP-05 | Application | missing cycle | `RESOLUTION_CYCLE_NOT_FOUND` |
| AP-06 | Application | completed cycle | `RESOLUTION_CYCLE_ALREADY_COMPLETED` |
| DB-01 | Integration | successful resolve | ticket/cycle/history/audit/outbox committed |
| DB-02 | Integration | metadata cleanup | waiting metadata cleared |
| DB-03 | Integration | rollback on cycle failure | no partial writes |
| DB-04 | Integration | rollback on outbox failure | no partial writes |
| CC-01 | Concurrency | same version, two resolves | one success, one conflict |
| ID-01 | Idempotency | identical replay | original response, one side effect |
| ID-02 | Idempotency | same key, different body | `IDEMPOTENCY_KEY_REUSED` |
| API-01 | Contract | missing If-Match | `428 PRECONDITION_REQUIRED` |
| API-02 | Contract | invalid code/summary | stable validation error |
| EVT-01 | Contract | resolved schema | valid |
| E2E-01 | E2E | assign -> start -> resolve | correct final state and trace |

## 3. Atomicity Assertions

After injected failure, assert unchanged:

- ticket status, resolution fields, waiting metadata, and version;
- resolution cycle status;
- status history count;
- timeline and audit count;
- outbox count;
- finalized idempotency response.

## 4. Security Assertions

- actor/body spoofing is ignored or rejected;
- Requesters cannot resolve tickets;
- queue scope predicates use the ticket's triaged queue;
- errors do not leak details from other queues;
- logs omit Authorization headers, idempotency keys, secrets, and full summary text.

## 5. Event Assertions

- exact event type `ticket.resolved.v1`;
- aggregate version equals stored ticket version;
- previous/new status matches history;
- resolution cycle ID matches the completed cycle;
- no secrets, raw claims, or full identity profile.

## 6. Observability Assertions

Verify counters/timers:

```text
ticket_resolution_commands_total{resolution_code,outcome}
ticket_resolution_conflicts_total{type}
ticket_resolution_duration_seconds
```

Metric labels exclude ticket IDs, user IDs, summaries, and idempotency keys.

## 7. Exit Criteria

- all tests deterministic and passing;
- no skipped authorization, rollback, concurrency, or contract tests;
- migration runs on a Phase 01-009 schema;
- OpenAPI and AsyncAPI pass validation;
- E2E proves traceability across ticket, cycle, history, timeline, audit, and outbox.
