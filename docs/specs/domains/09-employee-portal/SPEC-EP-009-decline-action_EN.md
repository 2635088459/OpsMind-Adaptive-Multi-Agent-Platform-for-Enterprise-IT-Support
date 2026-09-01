# SPEC-EP-009 — Decline Action

> Domain: `09-employee-portal` | Phase: 03 — Self-service Action Confirmation | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-009`, implements `UC-EP-03`'s decline path.

## 2. Objective
Call the (pending) decline endpoint, returning the turn to `IDLE` with zero backend side effect.

## 3. Design References
`04-use-cases` UC-EP-03 (alternate flow); `05-api-contracts` §2.3.

## 4. Actor
An employee viewing a `ProposedActionCard` who chooses not to proceed.

## 5. Scope
The `useDeclineAction` hook and the "Not now" button's behavior.

## 6. Non-goals
Any retry or re-proposal logic — declining simply ends this proposal; the employee may ask a new question via SPEC-EP-005 normally.

## 7. Preconditions
Turn state is `AWAITING_CONFIRMATION`.

## 8. Input
The `actionId` being declined.

## 9. Detailed Behavior
Click "Not now" → call decline → `IDLE`, with no execution attempted at any point.

## 10. Interaction State Transition
`AWAITING_CONFIRMATION → IDLE` directly, per `03-state-machine` §3.1's alternate edge.

## 11. Business Invariants
BI-EP-003 (a fortiori satisfied — no side effect at all).

## 12. Idempotency Strategy
`Idempotency-Key` per decline click, matching the confirm path's convention (`09-concurrency-and-idempotency` §3).

## 13. Consumed/Depended-on Contracts
The decline endpoint (pending, `SPEC-ARO-040`) — MSW-mocked until real.

## 14. Security
Same scope as SPEC-EP-008 (`conversations:confirm-action` covers both confirm and decline per the backend's own contract).

## 15. Observability
`traceparent` on the decline call.

## 16. Error Scenarios
Network failure on decline → retried like any other side-effecting call; a failed decline never silently proceeds as if declined (the card remains in `AWAITING_CONFIRMATION` until decline genuinely succeeds).

## 17. Acceptance Scenarios
Clicking "Not now" produces zero tool-request/approval-request rows (verified once the real backend exists, per `SPEC-ARO-040`'s own acceptance criteria).

## 18. Tests First
A component test asserting no execution-related network call is ever made on decline.

## 19. Definition of Done
Decline is proven to have zero side effect, both via the MSW mock's own call assertions and (once available) the real backend's database state.
