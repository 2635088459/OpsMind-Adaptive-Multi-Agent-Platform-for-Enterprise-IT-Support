# SPEC-TW-026 — Confirm Resolution

## 1. Goal

Confirm that a resolved ticket is accepted and move it to CLOSED.

## 2. Scope

Includes:

- `POST /v1/tickets/{ticketId}/resolution-confirmation`;
- `RESOLVED -> CLOSED`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.resolution-confirmed.v1`.

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
- Confirmation must reference the current resolution cycle and cannot close stale or superseded evidence.
