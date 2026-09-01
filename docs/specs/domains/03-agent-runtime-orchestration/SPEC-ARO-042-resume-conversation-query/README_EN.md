# SPEC-ARO-042 — Resume Conversation Query

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `04-use-cases`, `05-api-contracts`, `11-security`
>
> Document Status: Spec Planning

## 1. Goal

Implement `GET /api/v1/conversations/{conversationId}`, mapping the already-real `GET /{workflow_instance_id}` query to a conversation-shaped read model, plus a new query for "my most recent active/escalated conversation" supporting domain 09's UC-EP-06 (a returning employee who does not already know their `conversationId`).

## 2. Scope

Includes:

- The conversation-shaped read adapter over the existing `WorkflowQueryPort`;
- A new query capability: find the most recent active/escalated conversation belonging to a given requester identity.

Excludes:

- Any write path (this spec is entirely read-only);
- A full multi-conversation history list (explicitly a non-goal in domain 09's own roadmap).

## 3. Core Rules

- This spec introduces no new write path — it is read-only.
- `conversationId` continues to mean `workflowInstanceId` (SPEC-ARO-037); no parallel identity is introduced for querying.
- A conversation belonging to a different employee is never returned — authorization is enforced the same way `01-user-access-authentication`'s existing resource-ownership checks are enforced elsewhere in this platform.
