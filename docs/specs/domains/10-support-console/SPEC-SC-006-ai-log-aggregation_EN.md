# SPEC-SC-006 — AI Log Aggregation

> Domain: `10-support-console` | Phase: 03 — AI Transparency Panel | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-006`, implements `UC-SC-02`, the domain's single most architecturally distinct spec.

## 2. Objective
Build the `AiLogEntry` view — a unified timeline of what the agent did on a ticket — by concurrently fetching and merging 3 real, separately-owned endpoints, per the frontend-side aggregation decision recorded in `05-api-contracts` §3 (no new BFF service).

## 3. Design References
`01-domain-model` §"AiLogEntry"; `04-use-cases` UC-SC-02; `05-api-contracts` §3 (the aggregation architecture decision itself).

## 4. Actor
A support agent inspecting how the AI handled a ticket before it was escalated to them.

## 5. Scope
Concurrent fetch of `GET /api/v1/tickets/{id}/timeline`, `GET /api/v1/governance-audit-records`, `GET /api/v1/tool-requests/{id}` (all real, already implemented); client-side merge into one chronologically ordered `AiLogEntry` list.

## 6. Non-goals
Any new backend aggregation endpoint (explicitly rejected in favor of frontend-side aggregation, per the LLD's own architecture decision) — this spec must not silently drift toward wanting a BFF; if the 3-fetch approach proves genuinely insufficient, that reopens the LLD decision rather than being patched around here.

## 7. Preconditions
A ticket has an associated agent-processing history (i.e., was created via domain 09's conversational flow or otherwise touched by `agent-runtime-service`).

## 8. Input
The `ticketId`.

## 9. Detailed Behavior
Fire all 3 fetches concurrently (`Promise.allSettled`, not `Promise.all` — a partial failure must not blank the whole panel, per SPEC-SC-007); merge successful results into one timeline sorted by timestamp; each entry tagged with its source endpoint for traceability.

## 10. Interaction State Transition
N/A — a read-only aggregation view.

## 11. Business Invariants
BI-SC-003 (per domain 10's LLD, AI-log fidelity) — the merged timeline must never silently drop entries from a failed sub-fetch without indicating the gap (see SPEC-SC-007).

## 12. Idempotency Strategy
N/A — all 3 are `GET`s.

## 13. Consumed/Depended-on Contracts
All 3 real endpoints: ticket timeline (domain 02), governance audit records (domain 06), tool requests (domain 05) — cross-domain aggregation, the only spec in domain 10 touching 3 separate backend domains at once.

## 14. Security
Requires the union of scopes across all 3 endpoints — a genuine cross-domain authorization surface, flagged for the security phase (SPEC-SC-018/019) to audit specifically.

## 15. Observability
`traceparent` on all 3 concurrent fetches, ideally sharing a common parent span for this one aggregation operation.

## 16. Error Scenarios
Any one of the 3 fetches failing — handled by SPEC-SC-007's partial-degradation design, not silently ignored here.

## 17. Acceptance Scenarios
A ticket with entries from all 3 sources renders one unified, correctly time-ordered timeline.

## 18. Tests First
A component test with fixtures for all 3 endpoints, asserting correct chronological merge and source tagging.

## 19. Definition of Done
The merge logic is proven correct against fixtures matching all 3 real contract shapes; a compatibility test suite runs against the real endpoints.
