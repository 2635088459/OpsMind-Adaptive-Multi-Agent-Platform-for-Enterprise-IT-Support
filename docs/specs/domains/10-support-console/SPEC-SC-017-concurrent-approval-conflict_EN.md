# SPEC-SC-017 — Concurrent Approval Conflict

> Domain: `10-support-console` | Phase: 07 — Concurrency Hardening | Status: Implemented

## 1. Spec Identity
`SPEC-SC-017`, hardening SPEC-SC-009's grant/deny action against the real 3-way replay/conflict check built in `SPEC-PG-011`.

## 2. Objective
Prove SPEC-SC-009's grant/deny UI correctly surfaces the already-decided-differently outcome when two agents race to decide the same approval request — the scenario `SPEC-PG-011`'s backend logic was specifically built to guard against.

## 3. Design References
`SPEC-PG-011` (the backend contract this spec verifies against, per project memory: "strict 3-way grant/deny replay check"); SPEC-SC-009.

## 4. Actor
Two admins/agents viewing the same `ApprovalCard` (SPEC-SC-008) and racing to decide it.

## 5. Scope
A dedicated test scenario proving SPEC-SC-009 handles both of `SPEC-PG-011`'s real replay-check outcomes correctly. **Correction found live against `ApprovalService#decide`'s own code** (not just its spec prose): the `sameAttempt` check is a strict AND of `commandIdempotencyKey`, outcome, AND `decidedBy` all matching the existing decision — there are only 2 outcomes, not the 3 originally assumed here. A mismatch on ANY one of the three (including a *matching* outcome submitted with a different key or by a different actor) is still a conflict. The 2 real outcomes: same-decision-idempotent-replay (exact 3-way match — safe, returns the existing decision unchanged) and any-mismatch-is-a-conflict (covers both a differing outcome AND a same-outcome/different-key or -actor case alike, rejected and surfaced honestly).

## 6. Non-goals
Any new backend replay logic (already real and already the most rigorously specified contract this domain consumes, per project memory's description of `SPEC-PG-011`).

## 7. Preconditions
An approval request in a pending state, being decided concurrently by two actors.

## 8. Input
Two near-simultaneous grant/deny submissions.

## 9. Detailed Behavior
Both submissions race; the backend's real 3-way check per `SPEC-PG-011` determines the outcome; the second actor's UI (per SPEC-SC-009 §9) must render the actual first decision, not a generic failure.

## 10. Interaction State Transition
N/A — delegates to SPEC-SC-009's own state handling.

## 11. Business Invariants
BI-SC-005, exercised specifically against `SPEC-PG-011`'s own real backend guarantee — this spec's job is to confirm the frontend doesn't undermine a backend protection that already exists, not to build new protection.

## 12. Idempotency Strategy
Directly exercises `SPEC-PG-011`'s `commandIdempotencyKey` semantics from the client side.

## 13. Consumed/Depended-on Contracts
`SPEC-PG-011`'s grant/deny endpoints, specifically its documented 3-way check behavior.

## 14. Security
N/A beyond SPEC-SC-009's own requirements.

## 15. Observability
A conflict-outcome client event, useful for measuring real approval-race frequency.

## 16. Error Scenarios
This entire spec is the error/race scenario for SPEC-SC-009.

## 17. Acceptance Scenarios
Two racing decisions (one grant, one deny) resolve with the second actor seeing the actual winning decision, per `SPEC-PG-011`'s own defined behavior.

## 18. Tests First
An integration-style test simulating the race against `SPEC-PG-011`'s documented response contract (mocked until wired, then verified live given the endpoint is already real).

## 19. Definition of Done
All 3 of `SPEC-PG-011`'s outcomes are correctly reflected in SPEC-SC-009's UI; verified live against the real endpoint since it already exists.
