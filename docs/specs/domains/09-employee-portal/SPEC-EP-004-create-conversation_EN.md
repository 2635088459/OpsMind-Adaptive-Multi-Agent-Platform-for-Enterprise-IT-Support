# SPEC-EP-004 — Create Conversation

> Domain: `09-employee-portal` | Phase: 02 — Conversation Core | Status: Implemented

## 1. Spec Identity
`SPEC-EP-004`, implements `UC-EP-01`.

## 2. Objective
Call the (pending) `POST /api/v1/conversations` endpoint to start a new conversation, obtaining a real `conversationId`.

## 3. Design References
`01-domain-model` §"Conversation"; `04-use-cases` UC-EP-01; `05-api-contracts` §2.1.

## 4. Actor
A logged-in employee opening the portal with no active conversation.

## 5. Scope
The `useCreateConversation` hook and the empty-state UI that triggers it on first message.

## 6. Non-goals
Does not implement the backend endpoint itself — depends on `03-agent-runtime-orchestration`'s `SPEC-ARO-038`, mocked via MSW until that lands (Contract-first policy, roadmap §2.5).

## 7. Preconditions
`AUTHENTICATED`; no existing active/escalated conversation for this employee (see SPEC-EP-015 for the resume case).

## 8. Input
None (`{}` per `05-api-contracts` §2.1).

## 9. Detailed Behavior
On first message composition, call `POST /api/v1/conversations` → store the returned `conversationId` → proceed to SPEC-EP-005's send-message flow.

## 10. Interaction State Transition
Precedes the turn state machine's `IDLE` state (`03-state-machine` §3.1) — a conversation must exist before a turn can begin.

## 11. Business Invariants
None directly; sets up the precondition for BI-EP-001 (an employee only ever sees their own conversation) to hold from the start.

## 12. Idempotency Strategy
`Idempotency-Key` required, per `08-transaction-and-outbox` §2 — a retried creation call never produces two conversations.

## 13. Consumed/Depended-on Contracts
`POST /api/v1/conversations` (pending, `SPEC-ARO-038`) — mocked via MSW for this spec's own tests.

## 14. Security
Requires the (pending) `conversations:create` scope (`11-security-and-authorization` §2).

## 15. Observability
A `traceparent` header is generated for this call (`12-observability-and-audit` §1).

## 16. Error Scenarios
Backend unavailable → surfaces as a clear error, no fake `conversationId` is fabricated client-side.

## 17. Acceptance Scenarios
Against the MSW mock: a call returns a `conversationId`; against the real endpoint once built: the same, plus a real ticket appears in ticket-workflow's database (cross-checked in the compatibility test once `SPEC-ARO-038` exists).

## 18. Tests First
A contract test against the MSW mock, written before the UI.

## 19. Definition of Done
Passes against the mock now; a compatibility test is added (not rewritten) once the real backend endpoint exists.
