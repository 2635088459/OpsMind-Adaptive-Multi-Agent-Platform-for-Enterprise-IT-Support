# SPEC-SC-013 — Version Conflict Handling

> Domain: `10-support-console` | Phase: 05 — Manual Ticket Operations | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-013`, a cross-cutting hardening spec closing out Phase 05, covering SPEC-SC-010/011/012's shared concurrency risk.

## 2. Objective
Give a single, consistent UI treatment for the case where any of the 3 manual-operation specs' mutations are rejected by the backend due to a stale local version — the direct consequence of BI-SC-005's genuine multi-agent-collaboration risk (this domain, unlike domain 09, has multiple humans and the AI agent potentially touching the same ticket).

## 3. Design References
`01-domain-model` §BI-SC-005; `10-error-handling-and-reconciliation` §2.

## 4. Actor
Any agent/admin whose triage/assign/status-transition attempt loses a race to a concurrent change (by another human or by `agent-runtime-service` itself).

## 5. Scope
A shared conflict-handling utility used by SPEC-SC-010, 011, and 012: on a version-conflict (409-style) response, discard the local optimistic assumption, re-fetch the real current state, and show the agent exactly what changed underneath them before letting them retry.

## 6. Non-goals
Any backend optimistic-concurrency-control implementation (already real, per domain 02/06's own versioning) — this spec only consumes the conflict signal honestly.

## 7. Preconditions
A mutation from SPEC-SC-010/011/012 is rejected due to a version mismatch.

## 8. Input
The conflict response, including (where the backend provides it) the current real state that caused the rejection.

## 9. Detailed Behavior
On conflict: show a "this ticket changed since you loaded it" notice, display the actual current values next to the agent's attempted change, and offer to either discard or re-apply against the fresh state.

## 10. Interaction State Transition
N/A — a shared error-recovery UI pattern, not a new state machine.

## 11. Business Invariants
BI-SC-005 itself — this spec is its primary enforcement point at the UI layer.

## 12. Idempotency Strategy
Each retry after a conflict uses a fresh idempotency key against the now-current state, never blindly replaying the original stale-based request.

## 13. Consumed/Depended-on Contracts
The same 3 endpoints as SPEC-SC-010/011/012, specifically their conflict-response shape.

## 14. Security
N/A.

## 15. Observability
A version-conflict client event, useful for understanding real collision rates in production (informing whether domain 10's multi-agent-collaboration assumption needs further UI investment later).

## 16. Error Scenarios
This spec's entire content is the error scenario for the 3 mutating specs it hardens.

## 17. Acceptance Scenarios
Simulating a concurrent change before a triage submission surfaces the conflict notice with the real current values, not a generic error.

## 18. Tests First
A component test for each of the 3 mutation types hitting a simulated conflict response.

## 19. Definition of Done
All 3 mutation specs correctly delegate to this shared conflict-handling behavior; closes Phase 05.
