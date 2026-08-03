# SPEC-TW-008 — TDD Test Plan

## 1. TDD Order

### Step 1 — Aggregate Unit Tests

Write failing tests for valid and invalid assign, reassign, and unassign transitions before implementing aggregate methods.

### Step 2 — Eligibility and Authorization Tests

Test tenant, active state, support role, queue membership, actor role, operation permission, and queue scope.

### Step 3 — Handler Tests

Test orchestration order, dependency failures, error mapping, replay behavior, and response mapping.

### Step 4 — Repository Integration Tests

Use PostgreSQL/Testcontainers to verify optimistic updates, append-only history, indexes, constraints, and transaction rollback.

### Step 5 — API Contract Tests

Validate headers, payload constraints, Problem Details, response `ETag`, and OpenAPI examples.

### Step 6 — Event Contract Tests

Serialize each event, validate it against AsyncAPI, and check version/order/privacy rules.

### Step 7 — Concurrency and End-to-End Tests

Race commands at one version, retry requests, inject persistence failures, and verify the full timeline/audit/outbox result.

## 2. Minimum Test Matrix

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| UT-01 | Domain | assign TRIAGED/no owner | ASSIGNED, owner set, version +1 |
| UT-02 | Domain | assign wrong state | `INVALID_TICKET_STATE` |
| UT-03 | Domain | assign already owned | `TICKET_ALREADY_ASSIGNED` |
| UT-04 | Domain | reassign ASSIGNED | owner changes, state preserved |
| UT-05 | Domain | reassign IN_PROGRESS | owner changes, IN_PROGRESS preserved |
| UT-06 | Domain | reassign same user | validation/conflict, no mutation |
| UT-07 | Domain | unassign ASSIGNED | TRIAGED, owner cleared |
| UT-08 | Domain | unassign IN_PROGRESS/waiting | `INVALID_TICKET_STATE` |
| AP-01 | Application | eligible active queue member | aggregate invoked |
| AP-02 | Application | assignee missing | `ASSIGNEE_NOT_FOUND` |
| AP-03 | Application | assignee inactive | `ASSIGNEE_INACTIVE` |
| AP-04 | Application | not support-capable | `ASSIGNEE_NOT_SUPPORT_AGENT` |
| AP-05 | Application | not queue member | `ASSIGNEE_NOT_IN_QUEUE` |
| SEC-01 | Security | requester actor | `403`, no writes |
| SEC-02 | Security | actor lacks queue access | `403`, no writes |
| SEC-03 | Security | cross-tenant ticket/assignee | no information leak |
| DB-01 | Integration | successful assign | all required rows committed |
| DB-02 | Integration | successful reassign | no status-history row |
| DB-03 | Integration | successful unassign | status history committed |
| DB-04 | Integration | outbox insert failure | complete rollback |
| DB-05 | Integration | history insert failure | complete rollback |
| CC-01 | Concurrency | same version, two commands | one success, one conflict |
| ID-01 | Idempotency | identical replay | original response, one side effect |
| ID-02 | Idempotency | same key, new fingerprint | `IDEMPOTENCY_KEY_REUSED` |
| API-01 | Contract | missing If-Match | `400`/`428` per shared API policy |
| API-02 | Contract | malformed UUID/reason | `400 VALIDATION_ERROR` |
| EVT-01 | Contract | three event schemas | AsyncAPI valid |
| E2E-01 | E2E | TRIAGED → assign → reassign → unassign | complete trace and correct final state |

## 3. Atomicity Assertions

After injected failure, assert unchanged:

- ticket assignee, status, and version;
- assignment and status history counts;
- timeline and audit counts;
- outbox count;
- finalized idempotency response.

## 4. Security Assertions

- tenant predicates exist on every read/update;
- client actor/tenant fields are ignored or rejected;
- errors do not reveal another tenant's user or ticket;
- logs omit authorization headers and raw idempotency keys;
- timeline content is requester-safe.

## 5. Event Assertions

- exact event type and v1 schema;
- unique UUID event ID and UTC timestamp;
- aggregate ID is ticket ID;
- aggregate version equals stored version;
- reassign has both old/new owners and unchanged status;
- unassign has previous owner and no fabricated new owner;
- no secret or full identity profile.

## 6. Observability Assertions

Verify counters for:

```text
ticket_assignment_commands_total{operation,outcome}
ticket_assignment_conflicts_total{type}
ticket_assignment_duration_seconds{operation}
```

Keep ticket IDs, user IDs, reasons, and idempotency keys out of metric labels.

## 7. Exit Criteria

- all tests deterministic and passing;
- no skipped concurrency, authorization, rollback, or contract tests;
- migration runs on a Phase 01–007-compatible schema;
- OpenAPI and AsyncAPI pass validation;
- coverage includes every state and stable error code;
- E2E proves traceability across ticket, history, timeline, audit, and outbox.
