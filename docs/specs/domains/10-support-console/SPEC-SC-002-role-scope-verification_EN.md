# SPEC-SC-002 — Role Scope Verification

> Domain: `10-support-console` | Phase: 01 — Authenticated Session Foundation | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-002`, hardening SPEC-SC-001 with real role-based UI gating.

## 2. Objective
Show only the actions a logged-in agent/admin is actually authorized for, reading real roles/scopes off the session token issued by domain 01 — while honestly reflecting the known backend gap that `policy-approval-governance`'s `ApprovalController` itself has no fine-grained authorization today (per `11-security-and-authorization` §2).

## 3. Design References
`01-domain-model` §"UserSession"; `11-security-and-authorization` §1-2.

## 4. Actor
A logged-in support agent or admin.

## 5. Scope
Reading the token's roles/scopes and using them to conditionally render UI affordances (e.g., an admin-only reconciliation trigger vs. an agent's queue actions).

## 6. Non-goals
Any new backend authorization enforcement — this spec only ever hides/shows UI based on real token claims; it must never fabricate a permission boundary the backend does not itself enforce (the explicit, self-flagged gap in `11-security-and-authorization` §2 regarding `ApprovalController`).

## 7. Preconditions
A valid session exists (SPEC-SC-001).

## 8. Input
The session token's role/scope claims.

## 9. Detailed Behavior
Parse roles/scopes from the token; conditionally render admin-only vs. agent-visible UI; for the known `ApprovalController` gap, render the grant/deny UI to any authenticated user (matching real backend behavior) while flagging this honestly in code comments/documentation, not silently pretending finer-grained control exists.

## 10. Interaction State Transition
N/A — a rendering-conditional concern.

## 11. Business Invariants
A new invariant: the console never displays an action as unavailable due to role when the backend would actually accept it (and vice versa) except where a documented backend gap makes this impossible to fully resolve client-side.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
The session token's claim shape, as issued by domain 01 (real, already implemented).

## 14. Security
This spec is explicitly UI-convenience only, never a security boundary — reiterated per `11-security-and-authorization` §2's own honest framing.

## 15. Observability
N/A beyond standard session observability.

## 16. Error Scenarios
A token with unexpected/missing role claims — default to the most restrictive UI rendering, never the most permissive.

## 17. Acceptance Scenarios
An agent-role session does not see the admin-only reconciliation trigger; an admin-role session does.

## 18. Tests First
A component test rendering the console shell with different role-claim fixtures, asserting correct conditional rendering.

## 19. Definition of Done
UI gating verified for both agent and admin role fixtures; the `ApprovalController` gap is documented inline, not silently worked around with fake enforcement.
