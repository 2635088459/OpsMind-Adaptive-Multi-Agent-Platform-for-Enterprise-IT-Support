# SPEC-SC-020 — Trace Propagation Coverage

> Domain: `10-support-console` | Phase: 08 — Security and Release Hardening | Status: Implemented

## 1. Spec Identity
`SPEC-SC-020`, the final spec of domain 10's roadmap — closes out this domain's release-readiness phase and, alongside `SPEC-EP-023`, completes the entire 43-spec Feature Spec roadmap across both frontend domains.

## 2. Objective
Confirm every network call made anywhere in the support console propagates a `traceparent` header, so a single ticket's full lifecycle — AI processing, escalation, human triage/assignment/approval — is followable end-to-end across all backend domains it touches, matching the observability visual already agreed with the user.

## 3. Design References
`12-observability-and-audit` (all sections); every prior spec's own §15 Observability section; SPEC-SC-014 (which itself consumes this same tracing infrastructure).

## 4. Actor
N/A — an engineering audit activity.

## 5. Scope
A cross-cutting review of every fetch/EventSource call site across SPEC-SC-001 through SPEC-SC-019, confirming trace-context propagation — with particular attention to SPEC-SC-006's 3 concurrent calls, which should ideally share one parent span for the aggregation operation as a whole.

## 6. Non-goals
The OTel collector/backend tracing infrastructure itself (owned by `08-observability-platform`, already fully closed).

## 7. Preconditions
All prior domain-10 specs are implemented.

## 8. Input
Actual runtime request headers observed in tests/dev builds.

## 9. Detailed Behavior
For each network call site, confirm a valid W3C `traceparent` header is attached, generated fresh for a new root span or propagated from an existing one; for SPEC-SC-006 specifically, confirm all 3 concurrent calls share a common parent span rather than 3 disconnected traces.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
No network call in this app is untraceable — identical invariant to `SPEC-EP-023`.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
All contracts referenced across domain 10, re-examined collectively for this one property.

## 14. Security
N/A (trace headers carry no sensitive data by design).

## 15. Observability
This spec's entire content is an observability concern — the artifact produced is an audit report confirming full coverage, plus a manual verification that the full ticket-lifecycle trace (created via domain 09's conversation → escalated → triaged/assigned/approved in domain 10) is followable as one connected trace in the real observability platform.

## 16. Error Scenarios
Any call site found missing trace propagation is a finding, fixed before this spec closes.

## 17. Acceptance Scenarios
A full ticket lifecycle spanning both frontend apps produces one continuous, followable trace visible in the real OTel backend.

## 18. Tests First
A static audit of call sites plus one integration-style test asserting trace-context continuity, including SPEC-SC-006's shared-parent-span requirement.

## 19. Definition of Done
Zero untraced call sites across the support-console codebase; this closes domain 10's Phase 08 and its entire Feature Spec roadmap (SPEC-SC-001 through SPEC-SC-020), and — alongside `SPEC-EP-023` — completes all 43 Feature Specs across domains 09 and 10.
