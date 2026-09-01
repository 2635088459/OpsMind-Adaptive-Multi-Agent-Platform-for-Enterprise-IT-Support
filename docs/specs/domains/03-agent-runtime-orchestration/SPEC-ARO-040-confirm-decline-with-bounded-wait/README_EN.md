# SPEC-ARO-040 — Confirm/Decline With Bounded Wait

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `03-state-machine`, `08-transaction-and-outbox`, `10-failure-handling`
>
> Document Status: Spec Planning

## 1. Goal

Implement `POST /api/v1/conversations/{id}/actions/{actionId}/confirm` and `.../decline`. A new `AgentTaskState` value, `AWAITING_USER_CONFIRMATION`, pauses a task until the user responds. `confirm` on a low-risk action dispatches the real tool request and waits, bounded, for real completion; `confirm` on a high-risk action instead creates a real governance approval request — and the response always states honestly which of the two happened, never pretending instant completion for the approval branch.

## 2. Scope

Includes:

- The new `AWAITING_USER_CONFIRMATION` task state and its entry/exit transitions;
- The bounded-synchronous-wait execution path for the tool-dispatch branch (reusing the existing `tool.completed`/`tool.failed` consumer, SPEC-ARO-020);
- The real outbound call to `06-policy-approval-governance`'s request-approval endpoint for the high-risk branch;
- `decline`'s no-op (zero side effect) path.

Excludes:

- The message-turn execution that produced the `ProposedAction` in the first place (SPEC-ARO-039);
- Consuming the eventual `approval.granted`/`approval.rejected` events once a human decides — that is already SPEC-ARO-021's existing job, reused unchanged.

## 3. Core Rules

- `confirm`/`decline` require an `Idempotency-Key`; the same `actionId` can never be confirmed or declined a second time with a new real side effect — a repeat returns the current, real terminal state.
- The bounded wait's timeout is a configurable value, not hardcoded, with its real default determined during phase implementation via load testing — never an indefinite block.
- On timeout, the response states `"still-processing"` honestly; it never fabricates a `"done"` result.
- The high-risk branch always returns `"awaiting-approval"` and never attempts the bounded wait at all — approval genuinely requires a human, and the response must never suggest otherwise.
- `decline` triggers zero tool dispatch and zero approval request — verified by the complete absence of a corresponding `tool_requests`/`approval_requests` row.
