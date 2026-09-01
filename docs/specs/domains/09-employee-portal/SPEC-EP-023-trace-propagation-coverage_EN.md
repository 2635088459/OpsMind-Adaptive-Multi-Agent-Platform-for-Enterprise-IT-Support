# SPEC-EP-023 — Trace Propagation Coverage

> Domain: `09-employee-portal` | Phase: 08 — Security and Release Hardening | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-023`, the final spec of domain 09's roadmap — closes out this domain's release-readiness phase.

## 2. Objective
Confirm every network call made anywhere in the employee portal actually propagates a `traceparent` header, so a single conversation's request can be followed end-to-end across `agent-runtime-service`, `ticket-workflow-service`, and the OTel collector, per the observability visual (Agent Observability mockup) already agreed with the user.

## 3. Design References
`12-observability-and-audit` (all sections); every prior spec's own §15 Observability section.

## 4. Actor
N/A — an engineering audit activity.

## 5. Scope
A cross-cutting review of every fetch/EventSource call site across SPEC-EP-001 through SPEC-EP-022, confirming trace-context propagation.

## 6. Non-goals
The OTel collector/backend tracing infrastructure itself (owned by `08-observability-platform`, already fully closed).

## 7. Preconditions
All prior domain-09 specs are implemented.

## 8. Input
Actual runtime request headers observed in tests/dev builds.

## 9. Detailed Behavior
For each network call site, confirm a valid W3C `traceparent` header is attached, generated fresh for a new root span or propagated from an existing one where the call is part of a larger existing operation (e.g., a message send within an already-open conversation).

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
A new cross-cutting invariant: no network call in this app is untraceable.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
All contracts referenced across domain 09, re-examined collectively for this one property.

## 14. Security
N/A (trace headers carry no sensitive data by design, per `08-observability-platform`'s own conventions).

## 15. Observability
This spec's entire content is an observability concern — the artifact produced is an audit report confirming full coverage.

## 16. Error Scenarios
Any call site found missing trace propagation is a finding, fixed before this spec closes.

## 17. Acceptance Scenarios
A full conversation flow (create → send → confirm → escalate) produces one continuous, followable trace across frontend and backend spans.

## 18. Tests First
A static audit of call sites plus one integration-style test asserting trace-context continuity across a full conversation flow (once real backend endpoints exist; MSW-mocked assertions on header presence until then).

## 19. Definition of Done
Zero untraced call sites across the employee-portal codebase; this closes domain 09's Phase 08 and its entire Feature Spec roadmap (SPEC-EP-001 through SPEC-EP-023, all implemented as of their respective closures).
