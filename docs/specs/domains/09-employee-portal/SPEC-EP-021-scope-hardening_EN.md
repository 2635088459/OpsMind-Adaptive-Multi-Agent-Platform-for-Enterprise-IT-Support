# SPEC-EP-021 — Scope Hardening

> Domain: `09-employee-portal` | Phase: 08 — Security and Release Hardening | Status: Implemented

## 1. Spec Identity
`SPEC-EP-021`, a security-hardening pass across every spec written so far in this domain.

## 2. Objective
Audit every API call made anywhere in the employee portal and confirm each carries exactly the scope it declared in its own spec — no over-broad token requested, no missing scope silently working only because of a backend bug.

## 3. Design References
`11-security-and-authorization` (all sections); every prior spec's own §14 Security section.

## 4. Actor
N/A — an engineering/security audit activity, not an end-user-facing behavior.

## 5. Scope
A cross-cutting review of the token/scope actually attached to each of SPEC-EP-001 through SPEC-EP-020's network calls, checked against each spec's declared scope.

## 6. Non-goals
Any new backend scope definitions (owned by the respective backend domains' own specs).

## 7. Preconditions
SPEC-EP-001 through SPEC-EP-020 are implemented (or their MSW-mocked forms are in place for audit purposes).

## 8. Input
The actual runtime request headers observed in tests/dev builds.

## 9. Detailed Behavior
For each endpoint call site, assert the token scope matches exactly the declared requirement — flag both under-scoped (would 403 against a real backend) and over-scoped (client requesting more than it uses) cases.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
A new cross-cutting invariant: no network call in this app ever carries a scope broader than what its own spec declares needed.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
All contracts referenced by SPEC-EP-001 through SPEC-EP-020, re-examined here collectively.

## 14. Security
This spec's entire content is a security concern.

## 15. Observability
A scope-audit report is the artifact this spec produces (checked into the repo or CI output, not a runtime feature).

## 16. Error Scenarios
Any endpoint found using an incorrect scope is a finding, tracked and fixed as part of this spec's own closure, not deferred silently.

## 17. Acceptance Scenarios
Every call site in the app passes the scope audit with zero findings, or all findings are fixed before this spec closes.

## 18. Tests First
A static/lint-style check (or a manual audit checklist, if automation isn't practical) run against every hook making a network call.

## 19. Definition of Done
Zero unresolved scope findings across the entire employee-portal codebase.
