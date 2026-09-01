# SPEC-EP-020 — SSE Reconnect Hardening

> Domain: `09-employee-portal` | Phase: 07 — Resilience and Degradation | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-020`, hardening SPEC-EP-014's basic SSE connection.

## 2. Objective
Add reconnection-with-backoff and stale-state detection to the ticket-status stream so a dropped connection self-heals without manual page refresh.

## 3. Design References
`10-error-handling-and-reconciliation` §2.4; SPEC-EP-014.

## 4. Actor
An employee with the ticket-status panel open during a transient connectivity blip or backend restart.

## 5. Scope
Exponential backoff reconnection logic for the `EventSource`; a re-fetch of full state (SPEC-EP-013's GET) on successful reconnect, since SSE resumption may have missed events.

## 6. Non-goals
Offline detection itself (SPEC-EP-019 owns that — this spec assumes network is nominally up but the SSE connection specifically dropped).

## 7. Preconditions
An active SSE connection (SPEC-EP-014) has dropped unexpectedly (not due to unmount).

## 8. Input
The `EventSource`'s `onerror` event.

## 9. Detailed Behavior
On drop: attempt reconnect with exponential backoff (capped); on each reconnect attempt succeeding, re-fetch full ticket state via SPEC-EP-013's GET (to catch any events missed while disconnected) before resuming streaming.

## 10. Interaction State Transition
An internal reconnect state machine for the stream (connected → reconnecting → connected/failed), separate from the business turn-state machine.

## 11. Business Invariants
BI-EP-004 — never display stale status as if current; a "reconnecting" indicator is shown rather than silently freezing on the last-known value without indication.

## 12. Idempotency Strategy
The re-fetch-on-reconnect approach sidesteps event-dedup entirely by re-establishing full state rather than trying to determine which events were missed.

## 13. Consumed/Depended-on Contracts
Same as SPEC-EP-014 plus SPEC-EP-013's GET endpoint (real, already implemented) for the post-reconnect re-fetch.

## 14. Security
Same as SPEC-EP-014.

## 15. Observability
Reconnect-attempt-count and reconnect-success/failure client events.

## 16. Error Scenarios
Reconnection exhausts its backoff cap without success — panel shows an explicit "unable to get live updates, refresh to check" notice rather than pretending to still be live.

## 17. Acceptance Scenarios
Simulating a dropped SSE connection triggers reconnect-with-backoff, and a successful reconnect re-syncs full state before resuming the stream.

## 18. Tests First
A hook test simulating connection drop/recovery cycles, asserting backoff timing and the full-state re-fetch on reconnect.

## 19. Definition of Done
Reconnect logic verified against simulated drops of varying duration, including the exhausted-backoff terminal case.
