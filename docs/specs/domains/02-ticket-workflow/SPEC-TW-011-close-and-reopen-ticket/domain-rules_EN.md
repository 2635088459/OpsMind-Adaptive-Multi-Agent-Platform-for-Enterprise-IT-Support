# SPEC-TW-011 — Domain Rules

## 1. Status Transitions

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `RESOLVED` | `CLOSED` | `SM-011` | `TICKET_CLOSED` |
| `RESOLVED` | `IN_PROGRESS` | `SM-012` | `TICKET_REOPENED` |
| `CLOSED` | `IN_PROGRESS` | `SM-013` | `TICKET_REOPENED` |

Every unlisted close/reopen transition is rejected.

## 2. Close Invariants

Close must:

1. require current status `RESOLVED`;
2. require the current resolution cycle to exist and be resolved;
3. require a valid `closeReason`;
4. set `status = CLOSED`;
5. set `closedAt`, `closedBy`, and `closeReasonCode`;
6. clear `autoCloseDueAt` and `activeWorkflowId`;
7. mark the current resolution cycle as `CLOSED`;
8. retain assignee, resolved fields, and historical snapshots;
9. increment version.

Close must not:

- close directly from `IN_PROGRESS` or waiting states;
- change requester, category, priority, queue, or assignee;
- delete resolution summary;
- publish a reopen event.

## 3. Reopen Invariants

Reopen must:

1. require current status `RESOLVED` or `CLOSED`;
2. require non-empty and length-valid `reopenReason`;
3. close or archive the old current cycle;
4. create a new active resolution cycle;
5. set `currentResolutionCycleId` to the new cycle;
6. set `status = IN_PROGRESS`;
7. increment `reopenCount`;
8. clear current ticket `resolvedAt`, `resolvedBy`, `resolutionCode`, `resolutionSummary`, `closedAt`, `closedBy`, `closeReasonCode`, and `autoCloseDueAt`;
9. retain the previous assignee;
10. increment version.

The old cycle/history must retain the previous resolution and close snapshots. Clearing current ticket fields must not lose history.

## 4. Ownership Rules

Reopen does not pick a new assignee automatically. If the previous assignee remains active and queue-authorized, keep it. If the assignee is inactive:

- the ticket may still enter `IN_PROGRESS`;
- response returns `ownershipStatus = ASSIGNEE_INACTIVE`;
- later active-work commands must require reassignment first.

## 5. Reopen Window

The earlier state machine froze a seven-day reopen window after `CLOSED`. The current Phase 03 plan allows `CLOSED -> IN_PROGRESS` but does not require a hard window. Recommended implementation keeps a config:

```text
ticket.reopenWindow = P7D
```

When the window is enabled, expired reopen returns `422 REOPEN_WINDOW_EXPIRED`. If the product chooses no window for this phase, disable it explicitly in config and tests.

## 6. Idempotency Fingerprint

Close fingerprint includes:

- `ticketId`;
- expected version;
- `closeReasonCode`;
- normalized `closeReason`.

Reopen fingerprint includes:

- `ticketId`;
- expected version;
- `reopenReasonCode`;
- normalized `reopenReason`.

## 7. Security

The server derives actor from the token and never accepts `closedBy` or `reopenedBy` from the body. Logs, events, and metric labels do not contain idempotency keys, Authorization headers, secrets, or full reason text.
