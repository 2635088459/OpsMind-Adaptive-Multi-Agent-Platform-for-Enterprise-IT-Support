# SPEC-TW-040 — Compensation

## 1. Goal

Execute controlled compensating actions to realign the Ticket with external side effects or workflow state.

## 2. Scope

Includes:

- `/internal/v1/tickets/{ticketId}/compensations`;
- recovery command, case/attempt/audit records;
- idempotency, version, and state-machine guards;
- `ticket.compensation-executed.v1`.

Excludes:

- new primary Ticket happy path;
- bypassing Phase 01 to Phase 09 state machine or security rules;
- silently mutating historical events.

## 3. Core Rules

- Compensation must select a defined action and cannot run arbitrary SQL or arbitrary state mutation.
- Commands record actor, reason, correlationId, and causationId.
- Duplicate commands are idempotent.
- Recovery actions are auditable, replayable, and explainable.
