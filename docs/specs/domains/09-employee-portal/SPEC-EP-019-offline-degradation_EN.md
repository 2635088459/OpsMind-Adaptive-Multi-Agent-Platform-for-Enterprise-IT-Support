# SPEC-EP-019 — Offline Degradation

> Domain: `09-employee-portal` | Phase: 07 — Resilience and Degradation | Status: Implemented

## 1. Spec Identity
`SPEC-EP-019`, hardening the portal against the employee's own network being unavailable.

## 2. Objective
Detect that the browser itself has lost network connectivity and degrade gracefully — distinct from SPEC-EP-018's backend-outage case, since the correct UI message and recovery path differ (nothing to retry against until connectivity itself returns).

## 3. Design References
`10-error-handling-and-reconciliation` §2.3.

## 4. Actor
An employee whose device loses network connectivity mid-session.

## 5. Scope
A `navigator.onLine`-plus-heartbeat-based offline detector; a persistent (non-blocking) offline banner; disabling send/upload actions while offline; auto-recovery on reconnect.

## 6. Non-goals
Any offline-first data sync/queueing (out of scope for this domain's stated needs — a message composed while offline is preserved as a draft per BI-EP-006, not silently queued for auto-send).

## 7. Preconditions
None — this is a cross-cutting concern active throughout the app's lifetime.

## 8. Input
Browser online/offline events; a periodic lightweight heartbeat ping (since `navigator.onLine` alone is known to be unreliable).

## 9. Detailed Behavior
On detected offline: show a persistent banner, disable send/upload/confirm/decline actions app-wide; on reconnect: hide the banner, re-enable actions, and re-fetch any panel whose data may be stale (SPEC-EP-013).

## 10. Interaction State Transition
Orthogonal to the turn-state machine — an offline overlay that disables interaction regardless of the underlying turn state, restoring it unchanged on reconnect.

## 11. Business Invariants
BI-EP-006 — a draft in progress when offline hits is never lost.

## 12. Idempotency Strategy
N/A — no network calls are made while in this state by design.

## 13. Consumed/Depended-on Contracts
None — purely client-side detection.

## 14. Security
N/A.

## 15. Observability
An offline-duration client event, useful for understanding real-world connectivity issues affecting employees.

## 16. Error Scenarios
The heartbeat itself failing to reach the server is exactly the offline signal, not a separate error to handle.

## 17. Acceptance Scenarios
Simulating a network loss shows the banner and disables actions; a draft typed while offline remains intact after reconnect.

## 18. Tests First
A component test toggling online/offline events and asserting banner visibility and action disabling.

## 19. Definition of Done
Offline detection and recovery both verified in tests; draft preservation across an offline period confirmed.
