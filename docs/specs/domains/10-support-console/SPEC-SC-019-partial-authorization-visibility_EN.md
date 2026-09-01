# SPEC-SC-019 — Partial Authorization Visibility

> Domain: `10-support-console` | Phase: 08 — Security and Release Hardening | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-019`, a security-hardening spec unique to this domain's aggregation architecture, extending SPEC-SC-007's honest-degradation principle specifically to the authorization dimension.

## 2. Objective
Confirm that when a support agent lacks scope for one of SPEC-SC-006's 3 aggregated sources (e.g., an agent with `tickets:read` but not `governance-audit:read`), the AI log panel shows an honest "you don't have access to this data" notice for that source — distinct from SPEC-SC-007's "this source is unavailable" (an outage) — never silently omitting the section as if that source simply had no data.

## 3. Design References
`11-security-and-authorization` §3; SPEC-SC-006; SPEC-SC-007 (the outage case this spec is distinguished from).

## 4. Actor
A support agent with narrower scope than a full admin, viewing the AI log panel.

## 5. Scope
Distinguishing a 403-style authorization rejection from any other failure in SPEC-SC-006's `Promise.allSettled` handling, and rendering a distinct "access restricted" notice rather than reusing SPEC-SC-007's "temporarily unavailable" copy (which would incorrectly imply a retry might help).

## 6. Non-goals
Any new backend authorization logic — this only ensures the frontend correctly interprets and honestly labels an existing 403 response, never conflating it with a 5xx/timeout.

## 7. Preconditions
An agent's session lacks scope for at least one of SPEC-SC-006's 3 sources.

## 8. Input
A 403-style response from one of the 3 aggregated fetches.

## 9. Detailed Behavior
On a 403 specifically (distinguished from other error types by status code), render "You don't have permission to view this data" with no retry button (retrying cannot help, unlike SPEC-SC-007's outage case).

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
Extends BI-SC-003: a permission gap must never look like a data gap or an outage to the viewing agent — the distinction matters because the correct next action differs (request access vs. wait and retry).

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
Same 3 endpoints as SPEC-SC-006, specifically their 403 response shape.

## 14. Security
This spec's entire content is a security-transparency concern.

## 15. Observability
A permission-denied client event, distinct from SPEC-SC-007's failure event, useful for understanding real-world role-provisioning gaps.

## 16. Error Scenarios
A 403 on all 3 sources simultaneously — 3 distinct "access restricted" notices, not a single generic one.

## 17. Acceptance Scenarios
An agent lacking `governance-audit:read` sees an honest access-restricted notice for that one source while the other two render normally.

## 18. Tests First
A component test simulating a 403 on exactly one of the 3 sources, asserting the correct distinct copy and absence of a retry button.

## 19. Definition of Done
403 responses are correctly distinguished from outages across all 3 sources, with distinct, honest UI copy for each case; closes domain 10's Feature Spec roadmap alongside SPEC-SC-020.
