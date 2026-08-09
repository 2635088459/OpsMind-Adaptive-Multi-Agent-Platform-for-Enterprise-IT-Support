# SPEC-TW-038 — Replay Event

## 1. Goal

Safely replay outbox, consumer inbox, or DLQ messages while preserving idempotency and ordering guards.

## 2. Scope

Includes:

- `/internal/v1/tickets/events/replay`;
- recovery command, case/attempt/audit records;
- idempotency, version, and state-machine guards;
- `ticket.event-replay-recorded.v1`.

Excludes:

- new primary Ticket happy path;
- bypassing Phase 01 to Phase 09 state machine or security rules;
- silently mutating historical events.

## 3. Core Rules

- Replay must be idempotent by both original event id and replay attempt id.
- Commands record actor, reason, correlationId, and causationId.
- Duplicate commands are idempotent.
- Recovery actions are auditable, replayable, and explainable.
