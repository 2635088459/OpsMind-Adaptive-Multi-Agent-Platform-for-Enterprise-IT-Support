# SPEC-TW-041 — Data Integrity Repair

## 1. Goal

Scan and repair controlled data-integrity issues such as missing projections or history/audit/outbox mismatch.

## 2. Scope

Includes:

- `/internal/v1/tickets/integrity-repairs`;
- recovery command, case/attempt/audit records;
- idempotency, version, and state-machine guards;
- `ticket.integrity-repair-applied.v1`.

Excludes:

- new primary Ticket happy path;
- bypassing Phase 01 to Phase 09 state machine or security rules;
- silently mutating historical events.

## 3. Core Rules

- Repair must first produce a scan finding and repair plan before controlled repair execution.
- Commands record actor, reason, correlationId, and causationId.
- Duplicate commands are idempotent.
- Recovery actions are auditable, replayable, and explainable.
