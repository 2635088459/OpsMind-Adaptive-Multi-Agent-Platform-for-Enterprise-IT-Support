# SPEC-SC-003 — Queue List

> Domain: `10-support-console` | Phase: 02 — Ticket Queue View | Status: Implemented

## 1. Spec Identity
`SPEC-SC-003`, implements `UC-SC-01`.

## 2. Objective
Render the list of tickets assigned to or available for the current agent/team, using the real, already-implemented ticket-list endpoint (per domain 02).

## 3. Design References
`01-domain-model` §"TicketQueueRow"; `04-use-cases` UC-SC-01; `05-api-contracts` §1 (real ticket-list endpoint).

## 4. Actor
A support agent viewing their work queue.

## 5. Scope
Fetching and rendering the queue table: ticket ID, title, status, severity, assignee, last-updated.

## 6. Non-goals
Real-time polling (SPEC-SC-005); severity/SLA visual treatment specifics (SPEC-SC-004).

## 7. Preconditions
A valid, role-verified session (SPEC-SC-001/002).

## 8. Input
Optional query filters (status, assignee) — default view shows the agent's own team queue.

## 9. Detailed Behavior
On mount, fetch the real ticket-list endpoint with the current filter set and render as a table; empty state shown distinctly from a loading state.

## 10. Interaction State Transition
N/A — a read-only list view.

## 11. Business Invariants
BI-SC (mirroring BI-EP-004's spirit) — the queue must reflect real backend ticket state, never a client-cached stale view presented as current without indication.

## 12. Idempotency Strategy
N/A — a `GET`, naturally idempotent.

## 13. Consumed/Depended-on Contracts
The real, already-implemented ticket-list endpoint (per domain 02, confirmed live in the 2026-09-01 integration verification).

## 14. Security
Requires the agent's own `tickets:read` scope (team-scoped, per domain 02's own authorization model).

## 15. Observability
`traceparent` on the fetch.

## 16. Error Scenarios
Fetch failure → retry affordance, distinct from an empty-queue state.

## 17. Acceptance Scenarios
A queue with 3 tickets of varying status renders all 3 rows correctly from the real backend.

## 18. Tests First
A component test against the real ticket-list contract's response shape.

## 19. Definition of Done
The queue table renders correctly against both a fixture matching the real contract and (once available) a live integration check.
