# Agent Runtime Orchestration — Phase 10: Conversational Intake

> **Document ID:** IMP-ARO-P10
> **Domain:** `03-agent-runtime-orchestration`
> **Status:** Draft (a new phase — domain 03's original 36 specs / phases 00-09 are all already implemented; this phase is added on top)
> **Trigger:** `09-employee-portal`'s LLD depends on a "conversation turn" capability that no domain currently owns — this phase fills that gap

---

## 1. Why phase 10, not a rewrite of an existing phase

Domain 03's phases 00-09 are already genuinely implemented and were marked "entire domain roadmap complete." This phase **rewrites nothing and overturns no existing design** — the `WorkflowInstance`/`AgentTask`/`Checkpoint`/`ToolRequest` domain model is fully reused; this phase only adds a new `workflow_type` and one new execution path on top of it.

## 2. What the real, existing code actually looks like (confirmed by reading the code, not guessed)

```text
A WorkflowInstance must be bound to an already-existing real ticketId — it can only be created
by consuming ticket.created.v1 (or calling the real POST / start_workflow, which likewise
requires a real ticket_id).

start_workflow requires the caller to supply a complete task_graph up front
(a pre-defined plan of task nodes).

AgentTask's real execution model is an asynchronous polling worker:
  POST /internal/agent-runtime/v1/agent-tasks/claim
  POST /internal/agent-runtime/v1/agent-tasks/{id}/complete

Every tool call (ToolRequest) requires a preceding_checkpoint_id (a real, existing business
invariant: "every external side effect must be preceded by a checkpoint write"), and tool
execution completion itself is asynchronous
(SPEC-ARO-019 dispatch, SPEC-ARO-020 consume tool.completed/failed).
```

These constraints directly shape this phase's design — they are not invented out of thin air.

## 3. Three architecture decisions already confirmed with the user

1. **Every conversation, even one that ultimately resolves in seconds, must first create a real ticket in `02-ticket-workflow`** — the existing `WorkflowInstance` constraint does not allow it to exist without a ticket. This is not a product preference; it is forced by the existing code structure.
2. **A new synchronous/inline execution path is introduced specifically for conversation turns**, not reusing the existing asynchronous claim/complete queue — confirmed with the user.
3. **Built inside `agent-runtime-service`**, as a new package (e.g. `interfaces/conversation/`), reusing the same `WorkflowInstance`/`AgentTask`/`Checkpoint` domain model and the same database schema — no new service is created — confirmed with the user.

## 4. A deeper finding that must be stated honestly: tool execution itself is still asynchronous today

Even if the conversation turn itself is made synchronous/inline, `ToolRequest`'s actual completion (SPEC-ARO-020 consuming `tool.completed`/`tool.failed`) **remains asynchronous** — that is how the Tool Gateway (`05-tool-integration-gateway`) itself is designed, and this phase cannot bypass it.

This means the experience shown in the `09-employee-portal` mockup — "see ✓ done within a few seconds of confirming" — is genuinely implemented as:

```text
Confirm request → this phase's new inline executor → writes a checkpoint → dispatches the ToolRequest
  → a short, bounded synchronous wait (e.g. 3-5 seconds; the exact value is left to load-testing during
    phase implementation)
    → the tool completes within the timeout → "done" is returned in the same HTTP response
    → the tool does not complete within the timeout → returns "still processing, you'll be updated,"
       and once the tool genuinely completes, it is picked up indirectly through domain 09's already-
       designed ticket-status SSE (05-api-contracts §2.4) — because by that point this conversation
       turn is equivalent to a real ticket status change
```

Tool execution is never pretended to be instantaneous — this is a real architectural constraint, honestly reflected in the response contract (see §6).

## 5. New domain concepts (extending the existing model, not building a parallel system)

```text
workflow_type = "conversational_intake"          (new enum value; all other WorkflowInstance fields unchanged)
task_type = "process_user_message"                (new enum value on the existing AgentTask field)
task_type = "execute_confirmed_action"            (new enum value)
```

`conversationId` (the frontend type already declared by domain 09) is exactly `workflowInstanceId` from this phase — no separate ID scheme is invented.

## 6. New APIs (public-facing, for the frontend — not `/internal/`)

```text
POST /api/v1/conversations
  → internally: calls 02-ticket-workflow's real POST /api/v1/tickets (source=API)
  → internally: creates a WorkflowInstance(workflow_type=conversational_intake) using the
    real ticketId just obtained (calling the internal command directly, not going through the
    ticket-created event-ingestion endpoint, since this ticketId was just created by this same
    request — no need to wait for an event round trip)

POST /api/v1/conversations/{conversationId}/messages
  → creates a new AgentTask(task_type=process_user_message)
  → this phase's new inline executor runs it synchronously: writes a checkpoint → queries
    04-memory-knowledge's knowledge base → decides: reply with text / propose an action /
    determine escalation is needed
  → the response shape matches the three-way choice already declared in domain 09's
    05-api-contracts §2.2

POST /api/v1/conversations/{conversationId}/actions/{actionId}/confirm
POST /api/v1/conversations/{conversationId}/actions/{actionId}/decline
  → confirm triggers the "bounded synchronous wait" execution path described in §4
  → if the proposal's risk level reaches the threshold requiring real approval (HIGH/CRITICAL,
    the exact threshold following 06-policy-approval-governance's existing RiskLevel semantics),
    the domain's real request-approval path is called, and the response explicitly states
    "awaiting human approval" — it never pretends this can complete instantly

GET /api/v1/conversations/{conversationId}
  → reuses the already-real GET /{workflow_instance_id} query capability, with a display-shape
    mapping layer for the conversation view
```

## 7. Escalation: not a second ticket — a triage of the same ticket

Because §3's decision 1 already means the ticket exists from message one, "escalating" is not "creating a ticket" — it is: this `conversational_intake` workflow instance determines it cannot/should not continue automating, and calls `02-ticket-workflow`'s real `POST /{ticketId}/triage` endpoint (with `actor_type` set to `AUTOMATION_AGENT` or an equivalent value — that endpoint already genuinely supports a non-human actor), routing the ticket to the correct support queue, after which this workflow instance concludes normally — handed off entirely to `10-support-console`'s agents, with no further automation attempted.

## 8. A necessary new infrastructure dependency: agent-runtime-service needs a real service identity

`agent-runtime-service` calling `02-ticket-workflow`'s/`06-policy-approval-governance`'s real endpoints requires a real, valid JWT (both domains genuinely enforce authentication, confirmed during the 2026-09-01 integration verification). `agent-runtime-service` currently has **no** mechanism to obtain such a token. A new addition is required: a real Keycloak client_credentials client (structurally the same kind as the `integration-test-client` built for today's testing, but this one is a production-grade service identity for `agent-runtime-service` itself), granted the already-real `tickets:create`, `ticket:triage`, and similar scopes. This is a prerequisite for this phase, not optional.

## 9. New Feature Specs

```text
SPEC-ARO-037-conversational-intake-workflow-type
SPEC-ARO-038-start-conversation-creates-ticket
SPEC-ARO-039-inline-message-turn-execution
SPEC-ARO-040-confirm-decline-with-bounded-wait
SPEC-ARO-041-escalation-via-existing-triage
SPEC-ARO-042-resume-conversation-query
SPEC-ARO-043-service-identity-for-outbound-calls
```

## 10. What this phase explicitly does not do

- Does not redesign `02-ticket-workflow`'s/`06-policy-approval-governance`'s existing real endpoints — only integrates with them as a caller
- Never pretends tool execution always completes synchronously (already honestly stated in §4)
- Does not build long-term, personalized multi-turn memory (the "per-user RAG isolation" gap already recorded in domain 09's own memory) — not solved by this phase, left to `04-memory-knowledge`'s own future design
- Does not change the semantics of any existing `WorkflowInstance`/`AgentTask` field — only adds new enum values

## 11. Exit Criteria

```text
Genuinely create a conversation → genuinely see the corresponding ticket in ticket-workflow's database
Send a message → synchronously receive the agent's text reply (via 04-memory-knowledge's real retrieval)
Propose a low-risk action → confirm → see a real tool-execution result within the bounded timeout, or a clear "still processing" notice
Propose a high-risk action → confirm → genuinely see an approval request appear in policy-approval-governance
Determine the request cannot be handled → genuinely call the triage endpoint, the ticket appears in support-console's queue
```

## 12. Immediate Next Steps

```text
1. Review this phase plan, especially §4's bounded synchronous wait approach (the timeout duration needs real load testing)
2. Build out §8's service-identity infrastructure (the Keycloak client)
3. Write SPEC-ARO-037~043 one by one
4. Verify end-to-end on the real docker-compose stack (reusing the Keycloak/RabbitMQ/Postgres environment already stood up on 2026-09-01)
```
