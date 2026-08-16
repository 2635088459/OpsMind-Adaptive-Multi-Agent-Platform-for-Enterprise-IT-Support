# SPEC-MK-003 — Outbox Idempotency Audit Baseline

> Domain: Memory Knowledge
>
> Phase: 00 — Engineering Foundation
>
> Service: `memory-knowledge-service`
>
> LLD Mapping: `08-transaction-and-outbox, 09-concurrency-and-idempotency, 12-observability`
>
> Document Status: Spec Planning

## 1. Goal

Create processed_events, outbox_events, audit records, poison/replay base contracts.

## 2. Scope

Includes:

- domain/application/infrastructure/interface design required by this spec;
- persistence, API/event contract, tests, and acceptance criteria;
- boundary consistency with 02 Ticket Workflow and 03 Agent Runtime.

Excludes:

- mutating 02 Ticket state;
- mutating 03 Workflow state;
- direct Tool execution;
- unvalidated active-memory writes;
- cross-domain distributed transactions.

## 3. Core Rules

- All consumed events dedup by eventId+consumerName; all published events go through outbox.
- Memory results must have provenance.
- Sensitive data must be redacted or rejected.
- State-changing commands must have idempotency or version protection.
- Event consumption must use processed-event deduplication.
- Event publication must go through the Memory outbox.
