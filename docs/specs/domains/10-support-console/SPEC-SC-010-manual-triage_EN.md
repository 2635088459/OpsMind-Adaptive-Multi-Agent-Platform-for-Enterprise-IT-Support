# SPEC-SC-010 — Manual Triage

> Domain: `10-support-console` | Phase: 05 — Manual Ticket Operations | Status: Implemented

## 1. Spec Identity
`SPEC-SC-010`, implements `UC-SC-04`.

## 2. Objective
Let an agent manually triage a ticket (set severity/category) via the real, already-implemented triage endpoint — the same endpoint domain 03's phase-10 escalation path calls (`SPEC-ARO-041`), now exposed for direct human use too.

## 3. Design References
`04-use-cases` UC-SC-04; domain 02's real `POST /{ticketId}/triage` endpoint.

## 4. Actor
A support agent reviewing an untriaged or mis-triaged ticket.

## 5. Scope
A triage form (severity, category) and its call to the real endpoint.

## 6. Non-goals
Any new backend triage logic.

## 7. Preconditions
A ticket in a triage-eligible state.

## 8. Input
Selected severity and category.

## 9. Detailed Behavior
Submit form → call `POST /{ticketId}/triage` (real) → queue/card reflects updated severity (SPEC-SC-004's display).

## 10. Interaction State Transition
Ticket status per domain 02's own state machine — this spec only triggers a transition already defined there.

## 11. Business Invariants
BI-SC (fidelity) — the form must reflect the real resulting state, not an optimistic guess, after submission.

## 12. Idempotency Strategy
`Idempotency-Key` per submission, per domain 02's own convention for this endpoint.

## 13. Consumed/Depended-on Contracts
`POST /{ticketId}/triage` — real, already implemented and already proven live (this is the identical endpoint domain 03's `SPEC-ARO-041` escalation path calls).

## 14. Security
Requires the agent's own `tickets:triage` scope (per domain 02's authorization model).

## 15. Observability
`traceparent` on the call.

## 16. Error Scenarios
Submission failure — retry affordance, form state preserved.

## 17. Acceptance Scenarios
Triaging a ticket to "high"/"printer" updates the queue row correctly.

## 18. Tests First
A component test against the real triage contract's response shape.

## 19. Definition of Done
The form works correctly against fixtures matching the real contract; a live integration check confirms end-to-end.
