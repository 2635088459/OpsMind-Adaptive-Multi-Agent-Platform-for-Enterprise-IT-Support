# SPEC-EP-005 — Send Message

> Domain: `09-employee-portal` | Phase: 02 — Conversation Core | Status: Implemented

## 1. Spec Identity
`SPEC-EP-005`, implements `UC-EP-02`.

## 2. Objective
Send a message and render whichever of the three response shapes (text/proposedAction/escalation) comes back.

## 3. Design References
`01-domain-model` §"Message"; `04-use-cases` UC-EP-02; `05-api-contracts` §2.2.

## 4. Actor
A logged-in employee with an existing `conversationId`.

## 5. Scope
The `MessageComposer` component, the `useSendMessage` hook, and rendering all three response shapes.

## 6. Non-goals
Rendering a `ProposedActionCard`'s confirm/decline buttons (SPEC-EP-007) or an `EscalationNotice` (SPEC-EP-012) — this spec only routes the response to the right renderer, owned by those specs.

## 7. Preconditions
Turn state machine is `IDLE` (`03-state-machine` §3.1); a `conversationId` exists (SPEC-EP-004).

## 8. Input
`{text, attachmentRefs[]}`.

## 9. Detailed Behavior
Click send → `SENDING` → `AWAITING_AGENT` → one of three response shapes → `IDLE`/`AWAITING_CONFIRMATION`/`ESCALATED` per `03-state-machine` §3.1.

## 10. Interaction State Transition
The full turn state machine in `03-state-machine` §3.1, minus the confirm/decline sub-states owned by SPEC-EP-007/008/009.

## 11. Business Invariants
None of BI-EP-001~007 are violated by this spec alone; BI-EP-002 (attachment readiness) is enforced by the composer disabling send while any attachment is not `ready`.

## 12. Idempotency Strategy
`Idempotency-Key` per send attempt, reused on retry (`09-concurrency-and-idempotency` §1); the send button disables during `SENDING` as a second line of defense.

## 13. Consumed/Depended-on Contracts
`POST /api/v1/conversations/{id}/messages` (pending, `SPEC-ARO-039`) — MSW-mocked for now.

## 14. Security
Requires the (pending) `conversations:message` scope.

## 15. Observability
`traceparent` on every send.

## 16. Error Scenarios
Timeout/5xx → retries with backoff, then `AGENT_UNAVAILABLE` (`10-error-handling-and-reconciliation` §2.1), handled fully in SPEC-EP-018.

## 17. Acceptance Scenarios
E2E-EP-01 (`14-testing-strategy` §3.2): send a message, receive a plain-text reply.

## 18. Tests First
Unit tests for the response-shape router; a contract test against the MSW mock for all three shapes.

## 19. Definition of Done
All three response shapes render correctly; repeated send-button clicks never produce duplicate messages (verified by test, not just UI disabling).
