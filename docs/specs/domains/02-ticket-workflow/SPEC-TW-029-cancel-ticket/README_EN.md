# SPEC-TW-029 — Cancel Ticket

## 1. Goal

Cancel a non-terminal ticket and prevent further lifecycle advancement.

## 2. Scope

Includes:

- `POST /v1/tickets/{ticketId}/cancel`;
- `non-terminal mutable states -> CANCELLED`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.cancelled.v1`.

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
- Cancel is terminal and must reject future close, reopen, assignment, escalation, and resume commands.
