# SPEC-EP-008 — Confirm Action

> Domain: `09-employee-portal` | Phase: 03 — Self-service Action Confirmation | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-008`, implements `UC-EP-03`'s confirm path.

## 2. Objective
Call the (pending) confirm endpoint and render the resulting outcome (`done`/`still-processing`/`awaiting-approval`, per `SPEC-ARO-040`'s own contract) as an updating status card.

## 3. Design References
`04-use-cases` UC-EP-03; `05-api-contracts` §2.3; `SPEC-ARO-040` (the backend contract this spec depends on).

## 4. Actor
An employee viewing a `ProposedActionCard`.

## 5. Scope
The `useConfirmAction` hook and the execution-status card shown while `ACTION_EXECUTING`.

## 6. Non-goals
The backend's own bounded-wait/approval-routing logic — this spec only renders whichever of the three outcomes it receives, honestly, including `still-processing` and `awaiting-approval` (not just the "instant done" happy path the mockup visually emphasizes).

## 7. Preconditions
Turn state is `AWAITING_CONFIRMATION`.

## 8. Input
The `actionId` being confirmed.

## 9. Detailed Behavior
Click Confirm → button disables immediately → `ACTION_EXECUTING` → render `done` (success card) / `still-processing` (a "still working on it" notice, not a fake done) / `awaiting-approval` (an honest "waiting on a human" notice) → `IDLE`.

## 10. Interaction State Transition
`AWAITING_CONFIRMATION → ACTION_EXECUTING → IDLE` per `03-state-machine` §3.1; the three sub-outcomes are a rendering concern of this spec, not new top-level states.

## 11. Business Invariants
BI-EP-003 (never execute without explicit confirmation — enforced by the button-click being the sole trigger) and BI-EP-005 (never fabricate a `done` result when the real outcome is `still-processing`/`awaiting-approval`).

## 12. Idempotency Strategy
`Idempotency-Key` per confirm click; the button disables on click, preventing a second real trigger (`09-concurrency-and-idempotency` §3).

## 13. Consumed/Depended-on Contracts
The confirm endpoint (pending, `SPEC-ARO-040`) — MSW-mocked for all three outcome branches until it lands.

## 14. Security
Requires the (pending) `conversations:confirm-action` scope.

## 15. Observability
`traceparent` on the confirm call.

## 16. Error Scenarios
Execution genuinely fails (not a network error) → the next agent message (a new suggestion or escalation) is rendered normally by SPEC-EP-005/012 — this spec does not retry on its own.

## 17. Acceptance Scenarios
E2E-EP-02 (`14-testing-strategy` §3.2): confirm a low-risk action, see the execution-complete status; a separate scenario for the `still-processing`/`awaiting-approval` outcomes once the real backend exists.

## 18. Tests First
Component tests for all three outcome renderings against the MSW mock, written before wiring the real endpoint.

## 19. Definition of Done
All three outcomes render correctly and honestly; a compatibility test is added once `SPEC-ARO-040` is real.
