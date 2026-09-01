# SPEC-SC-012 — Manual Status Transition

> Domain: `10-support-console` | Phase: 05 — Manual Ticket Operations | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-012`, implements `UC-SC-06`.

## 2. Objective
Let an agent manually move a ticket through its real backend state machine (e.g., `IN_PROGRESS → RESOLVED`), using domain 02's existing status-transition endpoint.

## 3. Design References
`04-use-cases` UC-SC-06; domain 02's real ticket state machine and its transition endpoint.

## 4. Actor
A support agent working a ticket.

## 5. Scope
A status-transition control (e.g., a dropdown or button set) showing only the transitions valid from the ticket's current real state, and the call to the real endpoint.

## 6. Non-goals
Rendering/inventing new transitions not defined in domain 02's own state machine — this spec strictly mirrors what the backend allows.

## 7. Preconditions
A ticket in an agent-modifiable state.

## 8. Input
The chosen target status.

## 9. Detailed Behavior
The control renders only the valid next-states per the ticket's current status (fetched from domain 02's own state-machine metadata, or a hardcoded mirror of it validated in tests) → submit → call the transition endpoint → row/detail updates.

## 10. Interaction State Transition
Directly triggers domain 02's own real ticket state machine — this spec does not define a new one.

## 11. Business Invariants
BI-SC-005 — a transition attempted from a stale locally-cached status must be rejected honestly by the backend (a version-conflict case) rather than silently succeeding client-side, per SPEC-SC-013.

## 12. Idempotency Strategy
`Idempotency-Key` per transition submission.

## 13. Consumed/Depended-on Contracts
Domain 02's real ticket-status-transition endpoint.

## 14. Security
Requires the agent's own `tickets:transition` scope.

## 15. Observability
`traceparent` on the call.

## 16. Error Scenarios
An invalid-transition rejection (stale local state) — handled by SPEC-SC-013's version-conflict UI.

## 17. Acceptance Scenarios
Transitioning a ticket from `IN_PROGRESS` to `RESOLVED` succeeds and updates the display; an attempt at an invalid transition (e.g., `RESOLVED → IN_PROGRESS` when the backend only allows `RESOLVED → CLOSED`) is rejected and surfaced honestly.

## 18. Tests First
A component test for a valid transition and an invalid-transition rejection, against the real contract's response shapes.

## 19. Definition of Done
Both cases verified against fixtures; a live integration check confirms end-to-end against domain 02's real state machine.
