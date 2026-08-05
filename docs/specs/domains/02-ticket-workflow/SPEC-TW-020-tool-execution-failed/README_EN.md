# SPEC-TW-020 — Tool Execution Failed

## 1. Goal

Consume trusted `tool.execution.failed.v1`, record the failure result, and safely return the ticket to `IN_PROGRESS` or mark it `FAILED` based on failure class.

Known-safe failure means the tool created no unknown external side effect and work can continue or be replanned. Internal pipeline failure means the execution pipeline itself failed and requires explicit recovery.

## 2. Scope

Includes failure classification, producer/schema validation, reference matching, duplicate/stale classification, timeline, audit, and outbox.

Excludes blind retry, manual escalation handling, and Verification.

## 3. Core Rules

- Ticket status is `EXECUTING`;
- event matches the current execution attempt;
- known-safe failure returns to `IN_PROGRESS`;
- pipeline/internal failure may enter `FAILED`;
- unsafe/unknown side effect belongs to SPEC-TW-021;
- duplicate creates no duplicate business effect.
