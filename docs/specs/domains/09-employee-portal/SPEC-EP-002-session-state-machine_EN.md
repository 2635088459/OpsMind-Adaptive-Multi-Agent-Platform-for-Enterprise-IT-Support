# SPEC-EP-002 — Session State Machine

> Domain: `09-employee-portal` | Phase: 01 — Login and Session | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-002`.

## 2. Objective
Implement the full session lifecycle beyond initial login: silent token refresh near expiry, and correct transition to `SESSION_EXPIRED` when refresh fails.

## 3. Design References
`03-state-machine` §3.3 (the full `AUTHENTICATED ⇄ TOKEN_REFRESHING ⇄ SESSION_EXPIRED` machine).

## 4. Actor
A logged-in employee, over the lifetime of a session.

## 5. Scope
Background token-expiry detection, silent refresh, and the `SESSION_EXPIRED` transition + re-login prompt.

## 6. Non-goals
Does not implement BI-EP-006's draft-preservation behavior — that is SPEC-EP-003, layered on top of this state machine's `SESSION_EXPIRED` transition.

## 7. Preconditions
`AUTHENTICATED`.

## 8. Input
None (background, time-driven).

## 9. Detailed Behavior
Near expiry, a silent refresh request is attempted; success keeps the state `AUTHENTICATED`; failure (revoked, refresh-token expired) moves to `SESSION_EXPIRED`.

## 10. Interaction State Transition
`AUTHENTICATED → TOKEN_REFRESHING → AUTHENTICATED | SESSION_EXPIRED`, exactly as declared in `03-state-machine` §3.3.

## 11. Business Invariants
None directly; this spec is the mechanism BI-EP-006 (SPEC-EP-003) depends on.

## 12. Idempotency Strategy
Refresh attempts are naturally idempotent (re-attempting a refresh has no additional side effect beyond obtaining a fresh token).

## 13. Consumed/Depended-on Contracts
The same real Keycloak session mechanism as SPEC-EP-001.

## 14. Security
No new scope. Refresh happens transparently; no refresh token is ever exposed to frontend JS.

## 15. Observability
A metric for refresh success/failure rate is a useful future addition (`12-observability-and-audit` §4), not required for this spec's Definition of Done.

## 16. Error Scenarios
Refresh fails while the employee is mid-interaction → the interaction is not silently dropped; it surfaces via SPEC-EP-003's own draft-preservation path.

## 17. Acceptance Scenarios
A session nearing expiry is refreshed with no visible interruption; a genuinely revoked session correctly reaches `SESSION_EXPIRED`.

## 18. Tests First
Component/unit tests for each state transition in `03-state-machine` §3.3; an integration test simulating a real token near-expiry against the real Keycloak realm.

## 19. Definition of Done
All transitions in the session state machine are covered by tests and pass against the real Keycloak realm.
