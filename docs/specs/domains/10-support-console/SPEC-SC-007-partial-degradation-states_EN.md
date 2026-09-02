# SPEC-SC-007 — Partial Degradation States

> Domain: `10-support-console` | Phase: 03 — AI Transparency Panel | Status: Implemented

## 1. Spec Identity
`SPEC-SC-007`, the honest-failure-handling half of SPEC-SC-006.

## 2. Objective
When one or two of the 3 concurrent fetches in SPEC-SC-006 fail, render the timeline from the succeeding source(s) plus an explicit, per-source "unavailable" indicator — never a blank panel, and never a timeline silently missing entries with no indication.

## 3. Design References
`10-error-handling-and-reconciliation` §1; SPEC-SC-006.

## 4. Actor
A support agent viewing the AI log panel during a partial backend outage (e.g., domain 06's governance-audit-records endpoint is down but the other two are fine).

## 5. Scope
Per-source error state tracking from the `Promise.allSettled` result; a distinct "this source's data is unavailable" notice per missing source, alongside the timeline built from whatever did succeed.

## 6. Non-goals
Any retry logic beyond a manual refresh affordance (no auto-retry storm against a struggling backend).

## 7. Preconditions
SPEC-SC-006's aggregation is in progress or has completed with at least one rejected fetch.

## 8. Input
The `Promise.allSettled` results, including rejection reasons.

## 9. Detailed Behavior
Render the merged timeline from fulfilled sources; render a per-source banner naming exactly which source is unavailable (e.g., "Governance audit data is temporarily unavailable") with a manual retry button scoped to just that source.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
BI-SC-003 — a partial result must always be labeled as partial; the agent must never mistake an incomplete timeline for a complete one.

## 12. Idempotency Strategy
N/A — retries are plain re-fetches (`GET`s).

## 13. Consumed/Depended-on Contracts
Same 3 endpoints as SPEC-SC-006.

## 14. Security
N/A — no new scope.

## 15. Observability
A per-source failure client event, useful for correlating console-side degradation with backend incidents.

## 16. Error Scenarios
This spec's entire content is the error-scenario handling for SPEC-SC-006; the case of all 3 sources failing renders 3 distinct unavailable banners and an empty (not broken) timeline body.

## 17. Acceptance Scenarios
Simulating a failure of exactly one of the 3 sources renders the other two correctly merged, with one clearly labeled unavailable banner and a working per-source retry.

## 18. Tests First
A component test for each of the 7 non-trivial success/failure combinations across the 3 sources (excluding the all-succeed case, covered by SPEC-SC-006).

## 19. Definition of Done
All partial-failure combinations render honestly and distinctly, each with a working scoped retry.
