# SPEC-EP-016 — Confirm Resolution

> Domain: `09-employee-portal` | Phase: 06 — Resolution Feedback Loop | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-016`, a new use case for closing the loop once an issue appears resolved.

## 2. Objective
Let an employee explicitly confirm that a proposed/executed resolution actually fixed their issue, feeding this signal back as a real ticket-resolution-confirmation, not just a UI dismissal.

## 3. Design References
`01-domain-model` §"TicketStatusView"; `04-use-cases` (new use case, added alongside this spec since it wasn't separately enumerated in the original 6); ticket-workflow's own resolution-confirmation contract (existing, per domain 02).

## 4. Actor
An employee whose ticket has reached a resolved-pending-confirmation state.

## 5. Scope
A "Yes, this fixed it" / "No, still an issue" affordance shown against a resolved ticket, calling the real ticket-workflow confirmation endpoint.

## 6. Non-goals
Any new resolution logic — this only calls an existing `02-ticket-workflow` contract for confirmation, mirroring how the support console would show similar state.

## 7. Preconditions
The ticket's status (per SPEC-EP-013's panel) is a resolved/pending-confirmation state.

## 8. Input
The employee's yes/no response.

## 9. Detailed Behavior
"Yes" → call ticket-workflow's confirmation endpoint (real, already existing per domain 02) → ticket closes; "No" → SPEC-EP-017's reopen flow.

## 10. Interaction State Transition
This is a `TicketStatusView`-level concern (SPEC-EP-013's panel), not the conversational turn-state machine — it can be triggered independent of any active conversation turn.

## 11. Business Invariants
BI-EP-004 — the confirmation UI must reflect the real backend resolution state, never a locally-assumed one.

## 12. Idempotency Strategy
`Idempotency-Key` per confirm/decline click, consistent with domain 02's own idempotency conventions for this endpoint.

## 13. Consumed/Depended-on Contracts
Ticket-workflow's existing resolution-confirmation endpoint (real, per domain 02 — exact path to be confirmed against domain 02's own API contract doc before wiring).

## 14. Security
Requires the employee to be the ticket's own requester (enforced backend-side).

## 15. Observability
`traceparent` on the confirm/decline call.

## 16. Error Scenarios
Confirmation call fails — retry affordance, ticket status remains as last known.

## 17. Acceptance Scenarios
Confirming a resolved ticket transitions its status to closed, reflected in SPEC-EP-013's panel.

## 18. Tests First
A component test for both the yes and no paths against a mocked confirmation endpoint.

## 19. Definition of Done
Both paths work correctly against the mock; a compatibility test is added once the exact domain-02 endpoint path is confirmed and wired.
