# SPEC-TW-037 — Open Reconciliation Case

## 1. Goal

Open an auditable reconciliation case for unknown results, cross-service conflicts, stale results, or data inconsistency.

## 2. Scope

Includes:

- `/internal/v1/tickets/{ticketId}/reconciliation-cases`;
- recovery command, case/attempt/audit records;
- idempotency, version, and state-machine guards;
- `ticket.reconciliation-case-opened.v1`.

Excludes:

- new primary Ticket happy path;
- bypassing Phase 01 to Phase 09 state machine or security rules;
- silently mutating historical events.

## 3. Core Rules

- A reconciliation case is the recovery entry point and must not directly repair business state.
- Commands record actor, reason, correlationId, and causationId.
- Duplicate commands are idempotent.
- Recovery actions are auditable, replayable, and explainable.
