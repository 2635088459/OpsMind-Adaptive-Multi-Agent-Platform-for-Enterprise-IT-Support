# SPEC-EP-015 — Resume Conversation

> Domain: `09-employee-portal` | Phase: 05 — Escalation and Ticket Status | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-015`, implements `UC-EP-06`.

## 2. Objective
Let an employee return to a previous conversation (from a link, history list, or ticket-status panel) and see its full transcript plus current turn state, using the real `SPEC-ARO-042` resume-conversation query.

## 3. Design References
`01-domain-model` §"Conversation"; `04-use-cases` UC-EP-06; `05-api-contracts` §2.4; `SPEC-ARO-042` (the backend contract this spec depends on).

## 4. Actor
An employee reopening an existing conversation.

## 5. Scope
Fetching and rendering a conversation's full history and current turn state on load.

## 6. Non-goals
Any new conversation-listing UI beyond what's needed to navigate here (a conversation-history list surface is out of this spec's scope — entry is via a direct link/ticket-panel link for now).

## 7. Preconditions
A known `conversationId` (== `workflowInstanceId`, per domain 03's phase-10 design).

## 8. Input
The `conversationId`.

## 9. Detailed Behavior
On mount, call the resume query, render the full message transcript, and set the turn-state machine (SPEC-EP-006) to whatever state the backend reports (e.g., resuming mid-`AWAITING_CONFIRMATION` re-renders the `ProposedActionCard`, not a blank composer).

## 10. Interaction State Transition
The turn-state machine (`03-state-machine` §3.1) is seeded from the backend's reported state rather than starting fresh at `IDLE`.

## 11. Business Invariants
BI-EP-006 (draft preservation extends naturally here — a resumed conversation must not lose any state the backend actually holds); BI-EP-004 (never show a fabricated turn state different from what the backend reports).

## 12. Idempotency Strategy
N/A — a `GET`-style query, naturally idempotent.

## 13. Consumed/Depended-on Contracts
`SPEC-ARO-042` — resume-conversation query (pending on the backend) — MSW-mocked until real.

## 14. Security
Requires the employee to be the conversation's own owner (authorization enforced backend-side per `SPEC-ARO-042`'s own security section — the frontend does not duplicate this check, only surfaces a 403 honestly if it occurs).

## 15. Observability
`traceparent` on the resume call, correlated with the original conversation's trace where possible.

## 16. Error Scenarios
Conversation not found / not owned by caller → an honest "not found" state, never a blank/misleading screen; resume call fails transiently → retry affordance.

## 17. Acceptance Scenarios
Resuming a conversation mid-`AWAITING_CONFIRMATION` re-renders the exact pending `ProposedActionCard`, not a fresh composer.

## 18. Tests First
A component test seeding each possible turn state from a fixture and asserting correct re-render.

## 19. Definition of Done
All resumable turn states render correctly from the mock; a compatibility test is added once `SPEC-ARO-042` is real.
