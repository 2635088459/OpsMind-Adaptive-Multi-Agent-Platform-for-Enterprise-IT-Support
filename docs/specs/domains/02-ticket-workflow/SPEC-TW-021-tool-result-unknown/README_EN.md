# SPEC-TW-021 — Tool Result Unknown

## 1. Goal

Consume trusted `tool.execution.result-unknown.v1`, record uncertainty and potential external side effects, and prevent blind retry of the same Tool Execution.

Unknown result enters `ESCALATED`, reconciliation-required, or an explicit manual investigation path.

## 2. Scope

Included:

- unknown result event consumer;
- uncertainty/evidence persistence;
- `EXECUTING -> ESCALATED`;
- duplicate/stale/DLQ classification;
- timeline, audit, outbox;
- reconciliation hook.

Excluded:

- automatic retry;
- automatic compensation;
- manual investigation UI;
- Verification/Resolve.

## 3. Core Rules

- Ticket status is `EXECUTING`;
- event matches the current execution attempt;
- unknown side effect cannot return to `IN_PROGRESS` and auto-retry;
- evidence reference is stored;
- duplicate does not escalate twice;
- late completed event cannot silently overwrite unknown result.
