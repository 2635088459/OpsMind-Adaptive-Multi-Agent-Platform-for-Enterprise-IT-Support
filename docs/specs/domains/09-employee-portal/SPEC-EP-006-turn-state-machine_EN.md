# SPEC-EP-006 — Turn State Machine

> Domain: `09-employee-portal` | Phase: 02 — Conversation Core | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-006`.

## 2. Objective
Implement the turn state machine itself (`03-state-machine` §3.1) as a standalone, well-tested Zustand store, decoupled from any single component — so SPEC-EP-005/007/008/012 can each drive it without duplicating transition logic.

## 3. Design References
`03-state-machine` §3.1 in full; `13-package-and-class-design` §4 (state-management choices).

## 4. Actor
N/A — an internal state-management concern, not a user-facing behavior of its own.

## 5. Scope
The `turnState` Zustand store and its transition functions, unit-tested independently of any UI.

## 6. Non-goals
Does not render anything — purely the state container consumed by other specs' components.

## 7. Preconditions
None.

## 8. Input
Transition events (`sendMessage`, `agentResponded`, `confirmClicked`, etc.), not HTTP requests directly.

## 9. Detailed Behavior
Exposes exactly the states and transitions declared in `03-state-machine` §3.1 — `IDLE`, `SENDING`, `AWAITING_AGENT`, `AWAITING_CONFIRMATION`, `ACTION_EXECUTING`, `ESCALATED`, `AGENT_UNAVAILABLE`, and their declared edges only; an illegal transition is rejected (throws/no-ops) rather than silently accepted.

## 10. Interaction State Transition
This spec's own subject matter — see §9.

## 11. Business Invariants
Enforces BI-EP-003 structurally: there is no transition path from `AWAITING_CONFIRMATION` directly to a side-effecting state without passing through an explicit confirm event.

## 12. Idempotency Strategy
N/A at this layer — idempotency keys belong to the HTTP layer (SPEC-EP-005 etc.), not the state machine itself.

## 13. Consumed/Depended-on Contracts
None — a pure client-side state module.

## 14. Security
N/A.

## 15. Observability
N/A directly; other specs' own observability hooks read this store's state for context.

## 16. Error Scenarios
An attempted illegal transition (e.g. confirming from `IDLE`) is a programming error this spec's own tests catch, not a runtime user-facing error.

## 17. Acceptance Scenarios
Every legal transition in `03-state-machine` §3.1 is exercised by a test; every illegal one is asserted to be rejected.

## 18. Tests First
A full state-machine unit test suite, written before any component consumes the store.

## 19. Definition of Done
100% of the declared states/transitions in `03-state-machine` §3.1 are covered by unit tests.
