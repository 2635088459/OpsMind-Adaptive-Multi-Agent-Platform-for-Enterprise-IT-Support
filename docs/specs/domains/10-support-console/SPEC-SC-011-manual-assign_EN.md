# SPEC-SC-011 — Manual Assign

> Domain: `10-support-console` | Phase: 05 — Manual Ticket Operations | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-011`, implements `UC-SC-05`.

## 2. Objective
Let an agent/admin assign or reassign a ticket to a specific human agent, via domain 02's existing real assignment endpoint.

## 3. Design References
`04-use-cases` UC-SC-05; domain 02's real ticket-assignment endpoint.

## 4. Actor
An admin (or a team lead agent) redistributing queue workload.

## 5. Scope
An assignee-picker affordance and its call to the real endpoint.

## 6. Non-goals
Any new backend assignment logic, or an automatic/algorithmic assignment feature (explicitly a manual operation, per this phase's own name).

## 7. Preconditions
A ticket exists and the current user has assignment permission (per SPEC-SC-002's role gating).

## 8. Input
The selected assignee (a user/agent identity from domain 01's user directory).

## 9. Detailed Behavior
Select assignee → call the real assignment endpoint → queue row updates its assignee column.

## 10. Interaction State Transition
No ticket-status transition, purely an assignee field update.

## 11. Business Invariants
BI-SC-005 — reassigning a ticket someone else just claimed must be surfaced as a real conflict (see SPEC-SC-013), not silently overwritten.

## 12. Idempotency Strategy
`Idempotency-Key` per assignment submission.

## 13. Consumed/Depended-on Contracts
Domain 02's real ticket-assignment endpoint; domain 01's user-directory lookup for the picker's options.

## 14. Security
Requires an `tickets:assign` scope, gated to admin/lead roles per SPEC-SC-002.

## 15. Observability
`traceparent` on the call.

## 16. Error Scenarios
Assignment conflict (ticket reassigned concurrently) — handled per SPEC-SC-013.

## 17. Acceptance Scenarios
Assigning a ticket to a named agent updates the queue row's assignee column correctly.

## 18. Tests First
A component test against the real assignment contract and the user-directory lookup fixture.

## 19. Definition of Done
Assignment works correctly against fixtures; a live integration check confirms end-to-end.
