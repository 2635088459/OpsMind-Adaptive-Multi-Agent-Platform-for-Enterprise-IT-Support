# SPEC-EP-018 — Agent Unavailable Fallback

> Domain: `09-employee-portal` | Phase: 07 — Resilience and Degradation | Status: Implemented

## 1. Spec Identity
`SPEC-EP-018`, a resilience-phase spec with no dedicated use case of its own — it hardens SPEC-EP-005/006 against a specific backend failure mode.

## 2. Objective
Give the employee an honest, actionable path when `agent-runtime-service` itself is unreachable or errors out on a message send (not a normal escalation, a genuine backend outage).

## 3. Design References
`10-error-handling-and-reconciliation` §2.1; `03-state-machine` §3.1 (error edges).

## 4. Actor
An employee sending a message during a backend outage.

## 5. Scope
Detecting a send failure that is not a normal application-level response (5xx, timeout, network error) and rendering a distinct "the assistant is temporarily unavailable" notice with a manual-ticket-creation fallback link (to the existing, already-real ticket-creation endpoint).

## 6. Non-goals
Any backend retry/circuit-breaker logic (that's `agent-runtime-service`'s own concern); auto-retry of the failed message (the employee retries explicitly, per BI-EP-007-style honesty about state).

## 7. Preconditions
A message send (SPEC-EP-005) has failed with an infrastructure-level error rather than a normal response.

## 8. Input
The failed send's error type (timeout/5xx/network).

## 9. Detailed Behavior
On such a failure, turn state moves to a distinct `SEND_FAILED` sub-state (not silently back to `IDLE`), rendering the failed message with a retry button and a "create a ticket manually instead" link to the existing ticket-creation UI/endpoint.

## 10. Interaction State Transition
Extends `03-state-machine` §3.1 with an explicit failure edge from `SENDING` distinct from a normal agent response.

## 11. Business Invariants
BI-EP-006 (the failed draft is preserved, not lost) and BI-EP-004 (never claim the message was processed when it wasn't).

## 12. Idempotency Strategy
Retry reuses the same message's `Idempotency-Key` (per SPEC-EP-005 §12), so a retry after a transient failure cannot double-process if the original send actually landed server-side.

## 13. Consumed/Depended-on Contracts
The existing, already-real manual ticket-creation endpoint (per domain 02) as the fallback path.

## 14. Security
No new scope — reuses existing ticket-creation scope.

## 15. Observability
A send-failure client event, useful for alerting on backend outages from the frontend's own vantage point.

## 16. Error Scenarios
This spec's entire scope is an error scenario by definition; the corresponding "what if this fallback itself fails" case is out of scope (falls back further to a generic error boundary).

## 17. Acceptance Scenarios
A simulated 503 on send renders the `SEND_FAILED` state with both a retry button and a working manual-ticket link.

## 18. Tests First
A component test simulating each error type (timeout/5xx/network) and asserting the correct fallback UI.

## 19. Definition of Done
All three failure types render the honest fallback UI; the manual-ticket link is verified to navigate to the real, already-existing endpoint.
