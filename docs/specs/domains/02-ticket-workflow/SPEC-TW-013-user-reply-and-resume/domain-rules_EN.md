# SPEC-TW-013 — Domain Rules

## Status Transition

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_USER` | `IN_PROGRESS` | `SM-015` | `USER_REPLIED` |

## Invariants

- actor is the ticket requester;
- request is the current open request;
- message and request answered state commit in one transaction;
- `waiting_for_requester_since` is cleared on success;
- `approval_reference` is unchanged;
- closed/resolved/cancelled tickets do not resume;
- spoofed requesterId in the body is ignored or rejected.

## Old Requests

Reply to an old request must not resume the ticket. Product may store it as a normal message, but it must be marked `resumeApplied = false`.
