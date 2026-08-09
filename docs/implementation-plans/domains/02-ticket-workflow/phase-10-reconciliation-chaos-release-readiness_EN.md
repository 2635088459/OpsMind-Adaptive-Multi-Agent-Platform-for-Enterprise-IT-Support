# Phase 10 — Reconciliation, Chaos and Release Readiness Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 10
>
> Specs: `SPEC-TW-037` to `SPEC-TW-041`
>
> Prerequisite: Phase 01 to Phase 09 completed and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 10 proves that Ticket Workflow can recover safely from duplication, reordering, crashes, unknown results, DLQ messages, cross-service conflicts, and data inconsistency, reaching release readiness.

The core path is not a new business happy path. It is the recovery path:

```text
Unknown / Duplicate / Out-of-order / Crash Window
-> Reconciliation Case
-> Replay / Correction / Compensation / Repair
-> Auditable Stable State
```

## 2. Design Boundaries

- Phase 10 introduces no new primary happy path.
- Reconciliation must not bypass state machine guards.
- Replay must not break event idempotency.
- Correction events must be explicit, auditable, and traceable; history is not silently rewritten.
- Compensation advances only through defined compensating actions and never direct JPA entity mutation.
- Integrity repair opens a case before controlled repair execution.
- Chaos, performance, and release gates verify readiness; they do not replace business tests.
- Every recovery action preserves actor, reason, correlationId, causationId, and audit trail.

## 3. Phase 10 Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-037` | Open Reconciliation Case | Open a reconciliation case for unknown/conflict/inconsistency |
| 2 | `SPEC-TW-038` | Replay Event | Safely replay event, outbox, or DLQ messages |
| 3 | `SPEC-TW-039` | Correction Event | Publish explicit correction events for wrong facts |
| 4 | `SPEC-TW-040` | Compensation | Execute controlled compensating actions |
| 5 | `SPEC-TW-041` | Data Integrity Repair | Scan and repair controlled data-integrity issues |

## 4. Recovery Classes

| Class | Example | Handling |
|---|---|---|
| Duplicate | Same event/command arrives twice | Idempotent replay with duplicate decision |
| Out-of-order | Verification arrives before tool result | stale/retry/reconciliation case |
| Unknown Result | Tool result unknown or crash window | open case, wait for evidence or compensate |
| Broken Projection | timeline/query projection missing | integrity scan + repair case |
| Bad Fact | Published event contains wrong field | correction event, no history rewrite |
| Side Effect Conflict | external system state disagrees with Ticket | compensation action |

## 5. Events and Audit

Ticket Workflow publishes or records:

```text
ticket.reconciliation-case-opened.v1
ticket.event-replay-recorded.v1
ticket.correction-event-published.v1
ticket.compensation-executed.v1
ticket.integrity-repair-applied.v1
```

## 6. Exit Criteria

- `SPEC-TW-037` to `SPEC-TW-041` docs, code, contracts, and tests are closed.
- duplicate/replay/out-of-order/crash-window scenarios are verified.
- correction and compensation are auditable.
- integrity repair does not silently mutate business state.
- release gate covers golden path, recovery path, security hardening, and performance smoke.
- Phase 01 to Phase 09 core tests still pass.
