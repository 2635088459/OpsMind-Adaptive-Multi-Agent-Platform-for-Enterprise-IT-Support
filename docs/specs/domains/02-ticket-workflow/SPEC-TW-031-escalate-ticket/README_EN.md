# SPEC-TW-031 — Escalate Ticket

## 1. Goal

Escalate a ticket to a higher support lane while preserving the reason and current work context.

## 2. Scope

Includes:

- `POST /v1/tickets/{ticketId}/escalation`;
- `mutable non-terminal states -> ESCALATED`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.escalated.v1`.

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
- Escalation freezes automated progression until an explicit resume or cancel command.
