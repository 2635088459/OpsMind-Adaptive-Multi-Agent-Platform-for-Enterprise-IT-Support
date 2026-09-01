# SPEC-EP-001 — OIDC Login Redirect

> Domain: `09-employee-portal` | Phase: 01 — Login and Session | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-001`, implements the login precondition of `UC-EP-01`.

## 2. Objective
Redirect an unauthenticated employee through the real Keycloak Authorization Code + PKCE flow and land back in the portal `AUTHENTICATED`.

## 3. Design References
`01-domain-model` §"UserSession"; `03-state-machine` §3.3; `11-security-and-authorization` §1.

## 4. Actor
An unauthenticated employee.

## 5. Scope
The login-initiation button/redirect, the callback handling, and reading the resulting `OPSMIND_SESSION` cookie's presence to move to `AUTHENTICATED`.

## 6. Non-goals
Does not implement OIDC itself (reuses `01-user-access-authentication`'s already-real, already-verified flow) — see `project-level-integration-verification` memory for the exact proven round trip this spec rides on.

## 7. Preconditions
None — this is the entry point for an unauthenticated visitor.

## 8. Input
None (a simple navigation/redirect, no request body).

## 9. Detailed Behavior
`GET /oauth2/authorization/opsmind` → 302 to Keycloak → real credentials → 302 callback → `Set-Cookie: OPSMIND_SESSION` → redirect to the portal's home route.

## 10. Interaction State Transition
`UNAUTHENTICATED → LOGIN_IN_PROGRESS → AUTHENTICATED` (or back to `UNAUTHENTICATED` on failure), per `03-state-machine` §3.3.

## 11. Business Invariants
None of BI-EP-001~007 apply directly to login itself; BI-EP-006 begins to matter from the next spec onward.

## 12. Idempotency Strategy
N/A — a GET-based redirect flow, not a side-effecting command.

## 13. Consumed/Depended-on Contracts
`01-user-access-authentication`'s real OIDC session mechanism, already proven live.

## 14. Security
No new scope introduced. The session cookie is `HttpOnly`/`Secure`/`SameSite=Lax`; frontend JS never reads its value (`11-security-and-authorization` §1).

## 15. Observability
None specific to this spec beyond the platform's general trace propagation (`12-observability-and-audit`).

## 16. Error Scenarios
Login failure at Keycloak → returns to `UNAUTHENTICATED` with an error message; no session cookie is set.

## 17. Acceptance Scenarios
A real browser completes the full redirect round trip against a real Keycloak realm and lands `AUTHENTICATED`, reusing the exact flow already verified live 2026-09-01.

## 18. Tests First
E2E-EP-01's login segment (`14-testing-strategy` §3.2), written before the redirect UI itself.

## 19. Definition of Done
A real login round trip passes against the real docker-compose stack; no mock is used for this spec (unlike most of this domain's other specs).
