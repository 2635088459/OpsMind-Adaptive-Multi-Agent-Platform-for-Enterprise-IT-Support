# Phase 07 — Verification and Resolution Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 07
>
> Specs: `SPEC-TW-022` through `SPEC-TW-025`
>
> Prerequisites: Phase 01 through Phase 06 implemented and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 07 starts independent verification after Tool Execution and allows only trusted verification evidence to move a ticket into `RESOLVED`.

Core path:

```text
VERIFYING -> RESOLVED
```

It also covers verification retry, failure limit, unsafe result escalation, and complete resolution-cycle persistence.

## 2. Design Boundaries

- Tool success is not resolution;
- proposal is not verification;
- verification is bound to current ticket, workflow, resolution cycle, tool result, and attempt;
- late verification from an old workflow/cycle is recorded as stale only;
- the third verification failure or unsafe result enters `ESCALATED`;
- `RESOLVED` is not `CLOSED`;
- Phase 07 `SPEC-TW-025-resolve-ticket` means resolution based on trusted verification evidence, distinct from the Phase 03 manual resolve command.

## 3. Phase 07 Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-022` | Start Verification | Start verification attempt from tool result |
| 2 | `SPEC-TW-023` | Verification Success | Apply trusted successful verification |
| 3 | `SPEC-TW-024` | Verification Failure | Apply failure, retry, or escalation |
| 4 | `SPEC-TW-025` | Resolve Ticket | Complete resolution from verification evidence |

## 4. State Transitions

| Current | Target | Trigger |
|---|---|---|
| `VERIFYING` | `VERIFYING` | Start Verification |
| `VERIFYING` | `RESOLVED` | Resolve with trusted verification |
| `VERIFYING` | `IN_PROGRESS` | Retryable Verification Failure |
| `VERIFYING` | `ESCALATED` | Failure limit or unsafe result |
| `VERIFYING` | `FAILED` | Verification pipeline failure |

## 5. Events

Ticket Workflow publishes:

```text
ticket.verification-started.v1
ticket.verification-success-applied.v1
ticket.verification-failure-applied.v1
ticket.resolved-with-verification.v1
```

Ticket Workflow consumes:

```text
verification.completed.v1
verification.failed.v1
```

## 6. Exit Criteria

- `SPEC-TW-022` through `SPEC-TW-025` docs, code, migrations, contracts, and tests are complete;
- resolution references current trusted verification evidence;
- duplicate verification result is idempotent;
- conflicting terminal verification result enters reconciliation;
- stale workflow/cycle/attempt does not advance the ticket;
- resolution cycle is fully persisted and can be consumed by Phase 08 close/reopen.
