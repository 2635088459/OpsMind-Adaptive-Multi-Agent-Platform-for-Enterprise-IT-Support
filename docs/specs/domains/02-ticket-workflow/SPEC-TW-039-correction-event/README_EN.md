# SPEC-TW-039 — Correction Event

## 1. Goal

Correct wrong facts through explicit correction events while preserving original history and audit chain.

## 2. Scope

Includes:

- `/internal/v1/tickets/{ticketId}/correction-events`;
- recovery command, case/attempt/audit records;
- idempotency, version, and state-machine guards;
- `ticket.correction-event-published.v1`.

Excludes:

- new primary Ticket happy path;
- bypassing Phase 01 to Phase 09 state machine or security rules;
- silently mutating historical events.

## 3. Core Rules

- Correction events must not delete or rewrite original events.
- Commands record actor, reason, correlationId, and causationId.
- Duplicate commands are idempotent.
- Recovery actions are auditable, replayable, and explainable.
