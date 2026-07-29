# SPEC-TW-007 — Triage Ticket

> Domain: Ticket Workflow  
> Service: `ticket-workflow-service`  
> Phase: 03 — Ticket Lifecycle and Ownership  
> Status: Ready for implementation  
> Prerequisites: `SPEC-TW-001` through `SPEC-TW-006`

## 1. Objective

Convert an existing `OPEN` ticket into a classified, prioritized, queue-routed `TRIAGED` ticket that is ready for assignment.

This is a command-side vertical slice. A successful command must update the ticket, status history, timeline, audit log, idempotency record, and transactional outbox in one database transaction.

## 2. Business Outcome

Before triage, the ticket is only an unclassified request. After triage, support operations can answer:

- what type of issue it is;
- how urgent it is;
- which support queue owns the next step;
- who performed triage and when;
- whether the exact command has already been processed.

## 3. In Scope

- `POST /api/v1/tickets/{ticketId}/triage`;
- category and optional subcategory selection;
- priority selection;
- support queue routing;
- `OPEN → TRIAGED`;
- actor and queue authorization;
- optimistic locking through `If-Match`;
- command idempotency through `Idempotency-Key`;
- status history, timeline, audit, and outbox writes;
- `ticket.triaged.v1`;
- unit, integration, API contract, event contract, and concurrency tests.

## 4. Out of Scope

- AI or rule-based automatic classification;
- automatic priority scoring;
- ticket assignment or claiming;
- SLA timers or breach escalation;
- notifications;
- approval workflow;
- knowledge recommendations;
- agent tool execution or remediation.

## 5. Required Files

| File | Purpose |
|---|---|
| `README_EN.md` / `README_CN.md` | Scope, outcome, implementation order |
| `acceptance-criteria_EN.md` / `_CN.md` | Executable behavior and Definition of Done |
| `api-contract_EN.md` / `_CN.md` | HTTP request, response, and error contract |
| `domain-rules_EN.md` / `_CN.md` | Aggregate rules, authorization, and transaction flow |
| `persistence_EN.md` / `_CN.md` | Schema changes and persistence invariants |
| `event-contract_EN.md` / `_CN.md` | Timeline, audit, and domain event contract |
| `test-plan_EN.md` / `_CN.md` | TDD order and test matrix |
| `openapi.yaml` | Machine-readable HTTP contract |
| `asyncapi.yaml` | Machine-readable event contract |
| `V007__triage_ticket.sql` | PostgreSQL/Flyway reference migration |
| `examples.http` | Happy-path and failure request examples |

## 6. Canonical Command

```text
POST /api/v1/tickets/{ticketId}/triage
Authorization: Bearer <token>
If-Match: "7"
Idempotency-Key: 2df4faae-9862-4ee6-bca0-a3b8a3455aa0
X-Correlation-Id: 21ae628b-f15d-47d1-a937-1be0f85d4cd1
```

```json
{
  "categoryId": "11111111-1111-1111-1111-111111111111",
  "subcategoryId": "22222222-2222-2222-2222-222222222222",
  "priority": "HIGH",
  "supportQueueId": "33333333-3333-3333-3333-333333333333",
  "reason": "VPN access failure affects the requester's scheduled shift."
}
```

## 7. Success Invariants

- the pre-command status is `OPEN`;
- the post-command status is `TRIAGED`;
- category, priority, and queue are valid and active;
- the subcategory, when present, belongs to the selected category;
- the actor is derived from the authenticated identity, never the request body;
- the actor is authorized to triage into the target queue;
- the ticket version increases exactly once;
- one status-history row, one timeline entry, one audit record, and one outbox event are committed;
- a rollback leaves none of those writes visible;
- a repeated idempotency key with the same request returns the stored result without a second mutation;
- reuse of the same key with a different request is rejected.

## 8. Implementation Order

1. Freeze acceptance criteria and contracts.
2. Add failing domain tests.
3. Add the migration and repository mappings.
4. Implement `TriageTicketCommand` and its handler.
5. Add authorization and catalog validation.
6. Add atomic history, timeline, audit, idempotency, and outbox writes.
7. Add API, integration, contract, rollback, and concurrency tests.
8. Verify metrics, structured logs, and documentation.

Do not begin `SPEC-TW-008` until every acceptance criterion in this folder passes.

