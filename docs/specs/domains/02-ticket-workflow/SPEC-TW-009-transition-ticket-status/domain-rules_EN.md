# SPEC-TW-009 — Domain Rules

## 1. Command

```text
TransitionTicketStatus(ticketId, targetStatus, reason, waitingForRequesterSince, approvalReference, expectedVersion, idempotencyKey, actorContext)
```

Tenant, actor, roles, scopes, and queue claims must come from trusted authentication middleware.

## 2. Aggregate Method

```text
Ticket.transitionStatus(currentStatus, currentAssigneeId, targetStatus, reason, waitingMetadata, actor, occurredAt)
```

The Application Layer performs I/O-bound checks before invoking the aggregate: ticket guard loading, actor authorization, and version validation. The Aggregate owns ticket-internal state-machine invariants.

## 3. Single Transition Matrix

| Current Status | Target Status | transitionId | reasonCode |
|---|---|---|---|
| `ASSIGNED` | `IN_PROGRESS` | `SM-005` | `WORK_STARTED` |
| `IN_PROGRESS` | `WAITING_FOR_USER` | `SM-006` | `WAITING_FOR_USER` |
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | `SM-007` | `WAITING_FOR_APPROVAL` |
| `WAITING_FOR_USER` | `IN_PROGRESS` | `SM-008` | `WORK_RESUMED` |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-009` | `WORK_RESUMED` |

Every unlisted transition is rejected. `RESOLVED`, `CLOSED`, and reopen transitions belong to `SPEC-TW-010/011`.

## 4. Start Work

The Aggregate must:

1. require current status `ASSIGNED`;
2. require an existing assignee;
3. set status to `IN_PROGRESS`;
4. clear waiting metadata;
5. increment version exactly once;
6. produce a `TicketStatusChanged` fact.

## 5. Wait for User

The Aggregate must:

1. require current status `IN_PROGRESS`;
2. require an existing assignee;
3. set status to `WAITING_FOR_USER`;
4. store `waitingForRequesterSince`, defaulting to command time when omitted;
5. clear `approvalReference`;
6. increment version exactly once.

## 6. Wait for Approval

The Aggregate must:

1. require current status `IN_PROGRESS`;
2. require an existing assignee;
3. require nonblank `approvalReference`;
4. set status to `WAITING_FOR_APPROVAL`;
5. clear `waitingForRequesterSince`;
6. increment version exactly once.

## 7. Resume Work

The Aggregate must:

1. require current status `WAITING_FOR_USER` or `WAITING_FOR_APPROVAL`;
2. require an existing assignee;
3. set status to `IN_PROGRESS`;
4. clear all waiting metadata;
5. increment version exactly once.

## 8. Actor Authorization

- Requesters cannot transition ticket status.
- Support Agents may transition only within granted operation and queue scope.
- Support Leads may transition tickets in queues they manage.
- Automation Agents need an explicit service-identity scope.
- This SPEC recommends one shared scope: `ticket:transition`.

## 9. Invariants

- `IN_PROGRESS`, `WAITING_FOR_USER`, and `WAITING_FOR_APPROVAL` require an assignee.
- Waiting states require matching waiting metadata.
- Non-waiting states must not retain active waiting metadata.
- A generic status command must not triage, assign, resolve, close, or reopen.
- Status history is append-only.
- Each successful aggregate command increments version exactly once.

## 10. Concurrency and Idempotency

Repository updates must include `WHERE ticket_id = ? AND version = ? AND status IN (?)`. If zero rows are updated, classify the failure as `VERSION_CONFLICT` or `INVALID_STATUS_TRANSITION` from the loaded guard.

Check idempotency before side effects. Identical replays return the stored status, body, headers, and resource version. The same key with a different fingerprint is rejected.

## 11. Domain Fact

```text
TicketStatusChanged
```

Facts contain only identifiers, statuses, waiting metadata, actor, reason, and version information. They must not include bearer tokens, raw authorization claims, private messages, or full user profiles.

## 12. Application Handler Order

1. authenticate and validate transport data;
2. reserve or check idempotency;
3. load the ticket transition guard;
4. validate actor command scope;
5. validate queue-level authorization;
6. validate expected version;
7. invoke the Aggregate Method;
8. persist ticket, history, timeline, audit, and outbox in one transaction;
9. store the replayable response;
10. return the response and ETag.
