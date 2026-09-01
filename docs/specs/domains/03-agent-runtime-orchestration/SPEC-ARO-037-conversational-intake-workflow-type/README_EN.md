# SPEC-ARO-037 — Conversational Intake Workflow Type

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `01-domain-model`, `03-state-machine`
>
> Document Status: Spec Planning

## 1. Goal

Introduce the `conversational_intake` `workflow_type` and its two supporting `AgentTask` `task_type` values (`process_user_message`, `execute_confirmed_action`), plus this workflow type's fixed, internally-owned `task_graph` template — without changing the semantics of any existing `WorkflowInstance`/`AgentTask` field or state transition.

## 2. Scope

Includes:

- The new `workflow_type`/`task_type` enum values and their allowed-value migrations;
- The fixed `task_graph` template `conversational_intake` resolves internally (never supplied by the caller, unlike the existing generic `start_workflow` contract);
- Establishing that the public `conversationId` domain 09/10 consume is exactly this workflow type's `workflowInstanceId` — no parallel ID scheme.

Excludes:

- The actual execution logic of a message turn (SPEC-ARO-039) or a confirm/decline action (SPEC-ARO-040);
- Ticket creation (SPEC-ARO-038) or triage-based escalation (SPEC-ARO-041);
- Any change to an existing `workflow_type`'s behavior.

## 3. Core Rules

- Only new enum values are added; no existing enum value's meaning or transition rule changes.
- The `conversational_intake` task graph is fixed and internal — the caller of `POST /api/v1/conversations` never supplies one, unlike the pre-existing generic `start_workflow` endpoint.
- `conversationId` is never a new identity system — it is `workflowInstanceId`, reused as-is.
