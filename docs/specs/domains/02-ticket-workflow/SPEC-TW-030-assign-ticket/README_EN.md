# SPEC-TW-030 — Assign Ticket

## 1. Goal

Update ticket owner, queue, team, or assignee without implying progress or resolution.

## 2. Scope

Includes:

- `POST /v1/tickets/{ticketId}/assignment`;
- `mutable non-terminal states -> same lifecycle state`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.assigned.v1`.

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
- Assignment is an ownership mutation with its own audit version and must not rewrite resolution evidence.
