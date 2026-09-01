# SPEC-EP-017 — Reopen From Portal

> Domain: `09-employee-portal` | Phase: 06 — Resolution Feedback Loop | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-017`, the "No, still an issue" continuation of SPEC-EP-016.

## 2. Objective
Let an employee reopen a ticket they've declared unresolved, feeding this into the existing ticket-workflow reopen contract and prompting for what's still wrong.

## 3. Design References
`01-domain-model` §"TicketStatusView"; ticket-workflow's existing reopen contract (per domain 02).

## 4. Actor
An employee who declined SPEC-EP-016's resolution confirmation.

## 5. Scope
Calling the reopen endpoint and prompting the employee to describe what's still wrong, feeding that back into a new conversation turn (reusing SPEC-EP-005's send-message flow).

## 6. Non-goals
Any new reopen logic on the backend — this consumes domain 02's existing contract, exactly as `02-ticket-workflow`'s own reopen use case already defines it.

## 7. Preconditions
The employee has just declined resolution via SPEC-EP-016.

## 8. Input
An optional free-text note on what's still wrong.

## 9. Detailed Behavior
Call the reopen endpoint (real, per domain 02) → ticket status returns to an active state → prompt renders a message composer pre-focused for the employee's follow-up, sent via SPEC-EP-005 as a normal new message on the same conversation.

## 10. Interaction State Transition
Reopening does not reset the conversation's own turn-state machine — the next message flows through SPEC-EP-006 normally.

## 11. Business Invariants
BI-EP-004 — the panel must reflect the real reopened state, not an optimistic client-side guess.

## 12. Idempotency Strategy
`Idempotency-Key` per reopen click, matching domain 02's own convention for this endpoint.

## 13. Consumed/Depended-on Contracts
Ticket-workflow's existing reopen endpoint (real, per domain 02).

## 14. Security
Requires the employee to be the ticket's own requester (enforced backend-side).

## 15. Observability
`traceparent` on the reopen call.

## 16. Error Scenarios
Reopen call fails — retry affordance; ticket status remains as last known (closed/resolved) until reopen genuinely succeeds.

## 17. Acceptance Scenarios
Declining resolution reopens the ticket and prompts for follow-up detail, which flows into the conversation as a new message.

## 18. Tests First
A component test for the reopen call and the resulting composer prompt, against a mocked reopen endpoint.

## 19. Definition of Done
Reopen flow works correctly against the mock; a compatibility test is added once the exact domain-02 endpoint is confirmed and wired.
