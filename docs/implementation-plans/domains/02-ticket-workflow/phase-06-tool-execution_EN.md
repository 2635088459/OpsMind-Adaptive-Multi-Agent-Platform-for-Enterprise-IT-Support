# Phase 06 — Tool Execution Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 06
>
> Specs: `SPEC-TW-019` through `SPEC-TW-021`
>
> Prerequisites: Phase 01 through Phase 05 implemented and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 06 applies Tool Gateway execution results for authorized pending actions and moves the ticket toward verification, continued investigation, or manual escalation.

The earlier roadmap goal is:

```text
EXECUTING -> VERIFYING
```

plus known-safe failure, unknown result, and internal failure paths.

Current implementation keeps these boundaries:

- Phase 05 stores approval/policy authorization but does not execute tools;
- Phase 06 applies only trusted Tool Gateway execution results;
- tool success never resolves a ticket directly;
- only Verification can prove the issue is fixed;
- unknown side effects are not blindly retried.

## 2. State Model

Phase 06 introduces or confirms these execution states:

```text
IN_PROGRESS -> EXECUTING
EXECUTING -> VERIFYING
EXECUTING -> IN_PROGRESS
EXECUTING -> ESCALATED
EXECUTING -> FAILED
```

If the current code does not yet persist `EXECUTING` / `VERIFYING`, the real migration for this phase must update status constraints. If execution-in-progress is instead carried by an execution-attempt table, query projections must still express the business semantics of `EXECUTING` and `VERIFYING`.

## 3. Phase 06 Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-019` | Tool Execution Completed | Successful execution enters verification |
| 2 | `SPEC-TW-020` | Tool Execution Failed | Classify known failures and safely recover |
| 3 | `SPEC-TW-021` | Tool Result Unknown | Unknown side effects enter escalation or reconciliation |

## 4. Events

Ticket Workflow consumes:

```text
tool.execution.completed.v1
tool.execution.failed.v1
tool.execution.result-unknown.v1
```

Ticket Workflow publishes:

```text
ticket.tool-execution-completed-applied.v1
ticket.tool-execution-failed-applied.v1
ticket.tool-result-unknown-recorded.v1
```

## 5. Core Requirements

- Tool event comes from a trusted producer;
- schema-invalid or wrong-producer event goes to DLQ;
- `toolExecutionId` is the business idempotency key;
- event matches ticket, workflow, action, and authorization reference;
- stale event does not advance the ticket;
- duplicate event creates no duplicate business effect;
- success enters only `VERIFYING`;
- known-safe failure returns to `IN_PROGRESS`;
- pipeline/internal failure may enter `FAILED`;
- unknown result or unknown side effect enters `ESCALATED` or reconciliation-required.

## 6. Exit Criteria

- `SPEC-TW-019` through `SPEC-TW-021` docs, code, migrations, contracts, and tests are complete;
- tool success never resolves directly;
- tool execution strictly matches the current pending action;
- unknown result is not automatically retried;
- ToolExecutionId creates no duplicate business effect;
- Phase 07 can start verification from the stored tool result reference.
