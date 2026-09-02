# SPEC-SC-016 — Concurrent Triage Conflict

> Domain: `10-support-console` | Phase: 07 — Concurrency Hardening | Status: Implemented

## 1. Spec Identity
`SPEC-SC-016`, a targeted hardening spec applying SPEC-SC-013's shared conflict-handling utility specifically to the triage flow, with a scenario unique to this domain: the agent (AI) itself may re-triage a ticket via `SPEC-ARO-041` concurrently with a human's manual triage attempt.

## 2. Objective
Prove SPEC-SC-010's triage form correctly surfaces a conflict when `agent-runtime-service`'s own escalation-triage call (not just another human) lands concurrently.

## 3. Design References
`10-error-handling-and-reconciliation` §2; SPEC-SC-010; SPEC-SC-013; `SPEC-ARO-041` (the backend's own triage-calling path).

## 4. Actor
An agent manually triaging a ticket at the exact moment `agent-runtime-service` also calls the same triage endpoint as part of an escalation.

## 5. Scope
A dedicated test scenario (and any UI copy adjustment needed) for this specific human-vs-agent race, distinct from a human-vs-human race (which SPEC-SC-013 already covers generically).

## 6. Non-goals
Any new backend locking/ordering guarantee (domain 02's existing optimistic concurrency control, already real, is the sole enforcement mechanism — this spec only verifies the console's client behavior against it).

## 7. Preconditions
A ticket is mid-escalation (an agent-runtime-service triage call is in flight) while a human also submits SPEC-SC-010's form.

## 8. Input
Same as SPEC-SC-010, in this specific timing scenario.

## 9. Detailed Behavior
Whichever call reaches domain 02 second receives the version-conflict response; SPEC-SC-013's shared handler renders the actual resulting triage (which may have come from the agent, not the human) honestly.

## 10. Interaction State Transition
Delegates entirely to SPEC-SC-013's conflict-recovery flow.

## 11. Business Invariants
BI-SC-005, specifically exercised against an agent-vs-human race rather than only a human-vs-human one — the invariant's full stated scope in this domain's own LLD.

## 12. Idempotency Strategy
Same as SPEC-SC-010/013.

## 13. Consumed/Depended-on Contracts
Same as SPEC-SC-010, exercised alongside `SPEC-ARO-041`'s own triage call.

## 14. Security
N/A beyond SPEC-SC-010's own requirements.

## 15. Observability
The conflict-event's trace should ideally show both competing calls' spans for post-hoc analysis (a nice-to-have, not a hard requirement of this spec).

## 16. Error Scenarios
This entire spec is the error scenario being verified.

## 17. Acceptance Scenarios
Simulating a race where the agent's triage call lands first shows the human agent the AI-determined triage result via the conflict UI, not a generic error.

## 18. Tests First
An integration-style test simulating both calls racing against a mocked/real domain-02 endpoint.

## 19. Definition of Done
The agent-vs-human race is proven to resolve via the existing conflict-handling UI with no special-casing needed beyond what SPEC-SC-013 already provides.
