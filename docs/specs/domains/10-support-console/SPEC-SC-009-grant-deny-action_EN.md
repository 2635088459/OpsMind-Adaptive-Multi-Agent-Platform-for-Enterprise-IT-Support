# SPEC-SC-009 — Grant Deny Action

> Domain: `10-support-console` | Phase: 04 — Approval Handling | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-009`, implements `UC-SC-03`'s action half.

## 2. Objective
Let an agent/admin grant or deny SPEC-SC-008's approval request via the real, already-live grant/deny endpoints, which per project memory were proven working end-to-end on 2026-09-01.

## 3. Design References
`04-use-cases` UC-SC-03; domain 06's real `POST /approval-requests/{id}:grant`/`:deny` endpoints (per `SPEC-PG-011`'s `commandIdempotencyKey`-based replay protection).

## 4. Actor
An agent/admin viewing SPEC-SC-008's card.

## 5. Scope
The grant/deny buttons and their call to the real backend endpoints, including the required `commandIdempotencyKey`.

## 6. Non-goals
Any new backend decision logic (domain 06 is fully implemented through phase-02).

## 7. Preconditions
An approval request in a decidable state (not already decided).

## 8. Input
Grant or deny choice, optionally a decision note.

## 9. Detailed Behavior
Click grant/deny → generate a `commandIdempotencyKey` for this click → call the real endpoint → on success, the card updates to reflect the decision; on a 409-style "already decided differently" response (a real 3-way replay check per `SPEC-PG-011`), render the actual current decision rather than the one just attempted.

## 10. Interaction State Transition
Pending → Granted/Denied, or Pending → (already-decided-by-another, surfaced honestly per SPEC-SC-017).

## 11. Business Invariants
BI-SC-005 (never silently overwrite a concurrent decision) — enforced by relying on the backend's real 3-way idempotency-key check rather than any client-side assumption of exclusivity.

## 12. Idempotency Strategy
A `commandIdempotencyKey` per click, matching `SPEC-PG-011`'s exact contract — a retried click with the same key is safe; a second agent's differing decision is rejected by the backend, not silently allowed.

## 13. Consumed/Depended-on Contracts
`POST /approval-requests/{id}:grant`/`:deny` — real, already implemented (per `SPEC-PG-011`).

## 14. Security
Same known gap as SPEC-SC-002/SPEC-SC-008 — any authenticated user can currently call this; not fabricated as more restrictive client-side.

## 15. Observability
`traceparent` on the grant/deny call.

## 16. Error Scenarios
The already-decided-differently case (SPEC-SC-017's focus); a network failure — retry with the same idempotency key is safe by design.

## 17. Acceptance Scenarios
Granting a pending request updates the card to a granted state; a concurrent deny-then-grant race resolves per the backend's real 3-way check, surfaced honestly to the second actor.

## 18. Tests First
A component test for grant, deny, and the already-decided-by-another response, against fixtures matching the real contract.

## 19. Definition of Done
All 3 outcomes verified against fixtures; a live integration check against the real endpoints (already proven live per project memory) confirms behavior end-to-end.
