# SPEC-SC-001 — OIDC Login Redirect

> Domain: `10-support-console` | Phase: 01 — Authenticated Session Foundation | Status: Implemented

## 1. Spec Identity
`SPEC-SC-001`, the support-console analogue of `SPEC-EP-001` — same underlying real OIDC/Keycloak flow, distinct app/client registration.

## 2. Objective
Let a support agent/admin authenticate via the existing, already-real OIDC flow (per domain 01, `user-access-authentication-service`) and land in the console with a valid session.

## 3. Design References
`01-domain-model` §"UserSession"; `05-api-contracts` §1; domain 01's own OIDC contract (real, already implemented).

## 4. Actor
A support agent or admin opening the console unauthenticated.

## 5. Scope
Redirect-to-Keycloak, callback handling, and session establishment for the support-console's own registered OIDC client.

## 6. Non-goals
Any new backend auth logic (domain 01 is fully implemented) — this spec is purely the console-side wiring of an already-real capability, using a distinct client ID from the employee portal's own registration.

## 7. Preconditions
None — this is the console's entry point.

## 8. Input
None (redirect-initiated) then the OIDC callback's authorization code.

## 9. Detailed Behavior
Unauthenticated access → redirect to Keycloak (support-console client) → callback → token exchange → session established → land on the queue view (SPEC-SC-003).

## 10. Interaction State Transition
Identical pattern to `SPEC-EP-001`'s session lifecycle, per `03-state-machine` of this domain.

## 11. Business Invariants
BI-SC (session-related invariant, mirroring BI-EP-001's spirit) — no console screen renders without a valid session.

## 12. Idempotency Strategy
N/A — a browser redirect flow, not a mutating API call.

## 13. Consumed/Depended-on Contracts
Domain 01's real OIDC endpoints, under a distinct registered client for the support console.

## 14. Security
Requires the support-console's own Keycloak client registration with agent/admin-appropriate realm roles (distinct from the employee-portal client).

## 15. Observability
Login-success/failure client events, consistent with SPEC-EP-001's own convention.

## 16. Error Scenarios
Invalid/expired code, denied consent — same handling pattern as SPEC-EP-001.

## 17. Acceptance Scenarios
An unauthenticated agent is redirected, completes login, and lands on the queue view with a valid session.

## 18. Tests First
An E2E test against a real Keycloak test realm (mirroring SPEC-EP-001's own testing approach), since this is real infrastructure, not a pending contract.

## 19. Definition of Done
Login flow verified end-to-end against the real Keycloak instance; session persists correctly across a page reload.
