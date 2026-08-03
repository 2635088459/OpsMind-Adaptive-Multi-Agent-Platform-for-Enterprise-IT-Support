# SPEC-TW-011 — TDD Test Plan

## 1. TDD Order

1. Aggregate unit tests: close/reopen transitions, invariants, and field cleanup.
2. Resolution cycle tests: close current cycle, create new cycle on reopen, preserve old snapshots.
3. Authorization tests: Support Lead, Support Agent, Requester, Automation Agent, queue scope.
4. Handler tests: idempotency, versioning, error mapping, response, outbox mapping.
5. Repository integration tests: PostgreSQL conditional updates, CHECK constraints, rollback.
6. API contract tests: headers, payloads, Problem Details, ETag.
7. Event contract tests: `ticket.closed.v1` and `ticket.reopened.v1`.
8. Concurrency/E2E: resolve -> close -> reopen -> resolve -> close.

## 2. Test Matrix

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| CL-UT-01 | Domain | `RESOLVED -> CLOSED` | success |
| CL-UT-02 | Domain | `IN_PROGRESS -> CLOSED` | invalid transition |
| CL-UT-03 | Domain | invalid close reason | rejected |
| RO-UT-01 | Domain | `RESOLVED -> IN_PROGRESS` | new cycle |
| RO-UT-02 | Domain | `CLOSED -> IN_PROGRESS` | new cycle |
| RO-UT-03 | Domain | missing reopen reason | rejected |
| RO-UT-04 | Domain | inactive assignee | warning/status |
| AP-01 | App | close happy path | all ports once |
| AP-02 | App | reopen happy path | all ports once |
| AP-03 | App | missing scope | no writes |
| AP-04 | App | stale version | no writes |
| DB-01 | Integration | close commit | ticket/cycle/history/outbox |
| DB-02 | Integration | reopen commit | old/new cycle correct |
| DB-03 | Integration | failure rollback | no partial writes |
| ID-01 | Idempotency | close replay | one side effect |
| ID-02 | Idempotency | reopen replay | one side effect |
| CC-01 | Concurrency | close vs reopen | one winner |
| EVT-01 | Contract | closed event | valid |
| EVT-02 | Contract | reopened event | valid |
| E2E-01 | E2E | full lifecycle | final trace complete |

## 3. Atomicity

After fault injection, assert ticket, cycle, history, timeline, audit, outbox, and idempotency response did not partially commit.

## 4. Security

- spoofed body actor is ignored;
- unauthorized queue is rejected;
- Requester cannot call support close/reopen by default;
- errors do not reveal other queues;
- logs do not contain Authorization, idempotency key, secrets, or full reason text.

## 5. Exit Criteria

- all tests are deterministic;
- OpenAPI and AsyncAPI validate;
- migration runs on Phase 01-010 schema;
- E2E proves the complete Phase 03 lifecycle loop.
