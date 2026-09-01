# SPEC-ARO-039 — Inline Message Turn Execution

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `03-state-machine`, `04-use-cases`, `05-api-contracts`, `09-concurrency-and-idempotency`
>
> Document Status: Spec Planning

## 1. Goal

Implement `POST /api/v1/conversations/{conversationId}/messages`: synchronously execute one `process_user_message` `AgentTask` inline (bypassing the existing async claim/complete worker queue), returning one of exactly three response shapes — plain text, a proposed action, or an escalation notice.

## 2. Scope

Includes:

- The new inline executor for `task_type="process_user_message"`, distinct from the existing async worker path;
- Writing a checkpoint before querying `04-memory-knowledge`;
- The three-way response discriminator (`text` / `proposedAction` / `escalation`).

Excludes:

- Confirming/declining a proposed action (SPEC-ARO-040);
- Actually escalating via triage (SPEC-ARO-041) — this spec only produces the `escalation` response shape, the triage call itself belongs to SPEC-ARO-040/041's confirm-adjacent flow.

## 3. Core Rules

- The inline executor never uses the existing `claim`/`complete` async worker endpoints for this `task_type` — it runs entirely within the HTTP request handling this message.
- A checkpoint is written before any external call (knowledge retrieval, and later a tool dispatch or approval request), per the existing "every external side effect must be preceded by a checkpoint" invariant.
- The response is always exactly one of the three declared shapes — never a partial or ambiguous shape, and never silently defaults to plain text when the agent actually intended a proposal or escalation.
- A resubmitted `Idempotency-Key` for the same message never re-runs the underlying LLM/knowledge-retrieval call — it returns the original result.
