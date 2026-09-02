# SPEC-SC-018 — Scope Hardening

> Domain: `10-support-console` | Phase: 08 — Security and Release Hardening | Status: Implemented

## 1. Spec Identity
`SPEC-SC-018`, the domain-10 analogue of `SPEC-EP-021`, made more consequential here by SPEC-SC-006's genuine 3-domain aggregation surface.

## 2. Objective
Audit every API call made anywhere in the support console — across all 4 backend domains it touches (02, 05, 06, 08, and pending 07) — and confirm each carries exactly its declared scope, with special attention to SPEC-SC-006's union-of-3-scopes aggregation call.

## 3. Design References
`11-security-and-authorization` (all sections); every prior spec's own §14 Security section, especially SPEC-SC-006's §14.

## 4. Actor
N/A — an engineering/security audit activity.

## 5. Scope
A cross-cutting review of the token/scope attached to each of SPEC-SC-001 through SPEC-SC-017's network calls.

## 6. Non-goals
Any new backend scope definitions, and specifically not attempting to patch over the known `ApprovalController` fine-grained-authorization gap (SPEC-SC-002 §6) — that gap is documented, not silently worked around here.

## 7. Preconditions
SPEC-SC-001 through SPEC-SC-017 are implemented.

## 8. Input
Actual runtime request headers observed in tests/dev builds.

## 9. Detailed Behavior
For each endpoint call site (across all 4-5 backend domains touched), assert the token scope matches exactly the declared requirement; specifically verify SPEC-SC-006's 3 concurrent calls each carry their own correctly-scoped token rather than one over-broad token used for all 3.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
No network call in this app ever carries a scope broader than what its own spec declares needed — identical invariant to `SPEC-EP-021`, applied here across a wider cross-domain surface.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
All contracts referenced by SPEC-SC-001 through SPEC-SC-017, re-examined collectively.

## 14. Security
This spec's entire content is a security concern; the highest-stakes finding category is any accidental scope leakage across SPEC-SC-006's 3-domain aggregation.

## 15. Observability
A scope-audit report is the artifact this spec produces.

## 16. Error Scenarios
Any endpoint found using an incorrect scope is a finding, fixed before this spec closes.

## 17. Acceptance Scenarios
Every call site in the app passes the scope audit with zero findings.

## 18. Tests First
A static/lint-style check (or manual audit checklist) run against every hook making a network call.

## 19. Definition of Done
Zero unresolved scope findings across the entire support-console codebase, with the known `ApprovalController` gap explicitly re-confirmed as documented (not silently patched).
