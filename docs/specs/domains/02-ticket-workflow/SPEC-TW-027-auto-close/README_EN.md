# SPEC-TW-027 — Auto Close

## 1. Goal

Automatically close resolved tickets after the policy window expires without rejection or reopen.

## 2. Scope

Includes:

- `POST /internal/v1/tickets/{ticketId}/auto-close`;
- `RESOLVED -> CLOSED`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.auto-closed.v1`.

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
- The scheduler signal is advisory; the service recomputes eligibility under lock.
