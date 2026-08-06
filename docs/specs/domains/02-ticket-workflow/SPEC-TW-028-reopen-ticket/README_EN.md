# SPEC-TW-028 — Reopen Ticket

## 1. Goal

Reopen a resolved or policy-eligible closed ticket and create a new resolution cycle.

## 2. Scope

Includes:

- `POST /v1/tickets/{ticketId}/reopen`;
- `RESOLVED or CLOSED -> REOPENED`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.reopened.v1`.

Excludes:

- Phase 06 tool execution;
- Phase 07 verification evidence production;
- cross-domain data repair.

## 3. Core Rules

- The command must bind to the current Ticket version to avoid stale writes.
- The actor must be authorized for the action.
- Reason is required and written to timeline/audit.
- Duplicate idempotency keys return the same result.
- Terminal state commands are rejected.
- Reopen preserves previous evidence and starts a new work cycle before returning to IN_PROGRESS.
