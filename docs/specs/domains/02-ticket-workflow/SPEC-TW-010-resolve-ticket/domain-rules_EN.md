# SPEC-TW-010 — Domain Rules

## 1. Command

```text
ResolveTicket(ticketId, resolutionCode, resolutionSummary, expectedVersion, idempotencyKey, actorContext)
```

Tenant, actor, roles, scopes, and queue claims must come from trusted authentication middleware.

## 2. Aggregate Method

```text
Ticket.resolve(currentStatus, currentAssigneeId, currentResolutionCycleId, resolutionCode, resolutionSummary, actor, occurredAt)
```

The Application Layer loads the guard, validates actor authorization, checks version, and verifies that the resolution cycle exists and is incomplete. The Aggregate owns ticket-internal state-machine invariants.

## 3. State Transition

| Current Status | Target Status | transitionId | reasonCode |
|---|---|---|---|
| `IN_PROGRESS` | `RESOLVED` | `SM-010` | `TICKET_RESOLVED` |

Every unlisted transition is rejected. `RESOLVED -> CLOSED` belongs to `SPEC-TW-011`.

## 4. Resolve Rules

The Aggregate must:

1. require current status `IN_PROGRESS`;
2. require an existing assignee;
3. require a current resolution cycle;
4. require `resolutionCode` from the controlled enum;
5. require trimmed `resolutionSummary` to be nonblank and within length limits;
6. set status to `RESOLVED`;
7. set `resolvedBy`, `resolvedAt`, `resolutionCode`, and `resolutionSummary`;
8. clear waiting metadata;
9. retain current assignee;
10. increment version exactly once;
11. produce a `TicketResolved` fact.

## 5. Resolution Code

```text
FIXED
WORKAROUND_PROVIDED
DUPLICATE
REQUEST_FULFILLED
NOT_REPRODUCIBLE
USER_ERROR
NO_ACTION_REQUIRED
```

## 6. Resolution Cycle

The current resolution cycle must:

- belong to the same ticket;
- be active/in-progress;
- be incomplete;
- be completed in the same transaction with completed time, actor, resolution code, and summary snapshot.

Historical cycles must not be overwritten.

## 7. Actor Authorization

- Requesters cannot resolve tickets.
- Support Agents may resolve only tickets in authorized queues.
- Support Leads may resolve tickets in queues they manage.
- Automation Agents need an explicit service-identity scope.
- This SPEC recommends scope: `ticket:resolve`.

## 8. Invariants

- `RESOLVED` tickets require `resolved_at`, `resolved_by`, `resolution_code`, and `resolution_summary`.
- `RESOLVED` tickets retain the current assignee.
- `RESOLVED` tickets do not retain waiting metadata.
- Resolution cannot change category, queue, or assignee.
- Status history and resolution-cycle history are append-only, except completing the active cycle.
- Each successful command increments version exactly once.

## 9. Concurrency and Idempotency

Repository updates must include `WHERE ticket_id = ? AND version = ? AND status = 'IN_PROGRESS' AND current_support_user_id IS NOT NULL`.

Check idempotency before side effects. Identical replays return the stored status, body, headers, and resource version. The same key with a different fingerprint is rejected.

## 10. Domain Fact

```text
TicketResolved
```

Facts contain only identifiers, statuses, assignee, resolution code, summary, actor, version, and timestamps. They must not include tokens, raw claims, private messages, or full user profiles.

## 11. Application Handler Order

1. authenticate and validate transport data;
2. reserve or check idempotency;
3. load the ticket resolve guard;
4. validate actor command scope;
5. validate queue-level authorization;
6. validate expected version;
7. validate resolution cycle;
8. invoke the Aggregate Method;
9. persist ticket, resolution cycle, history, timeline, audit, and outbox in one transaction;
10. store the replayable response;
11. return the response and ETag.
