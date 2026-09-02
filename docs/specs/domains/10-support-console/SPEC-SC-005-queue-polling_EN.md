# SPEC-SC-005 — Queue Polling

> Domain: `10-support-console` | Phase: 02 — Ticket Queue View | Status: Implemented

## 1. Spec Identity
`SPEC-SC-005`, keeps SPEC-SC-003's queue current.

## 2. Objective
Keep the queue table reasonably fresh without a dedicated backend push mechanism, via interval-based polling (a deliberate choice distinct from SSE, appropriate for a list view rather than a single-entity status — see technology-baseline's polling-vs-SSE guidance).

## 3. Design References
`01-domain-model` §"TicketQueueRow"; TanStack Query's own refetch-interval mechanism (per the frozen technology baseline).

## 4. Actor
A support agent with the queue view open for an extended period.

## 5. Scope
A TanStack Query `refetchInterval` configuration on SPEC-SC-003's queue query, with pause-when-tab-hidden behavior.

## 6. Non-goals
SSE/push for the queue (a deliberate scope decision — a list of many tickets polling is simpler and sufficient here, unlike the single-ticket SSE stream in domain 09's SPEC-EP-014).

## 7. Preconditions
SPEC-SC-003's queue query is active.

## 8. Input
None — a timer-driven refetch.

## 9. Detailed Behavior
Refetch the queue list on a fixed interval while the tab is visible; pause polling when the tab is backgrounded (via the Page Visibility API) to avoid wasted requests; resume on focus.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
BI-SC (freshness) — the queue should never silently go stale for an extended active session without the agent being able to trust it's being refreshed.

## 12. Idempotency Strategy
N/A — repeated `GET`s, naturally idempotent.

## 13. Consumed/Depended-on Contracts
Same endpoint as SPEC-SC-003.

## 14. Security
N/A — no new scope.

## 15. Observability
N/A beyond the fetch's own trace propagation (inherited from SPEC-SC-003).

## 16. Error Scenarios
A transient poll failure does not clear the currently-displayed data — it is retried on the next interval, with the existing view remaining visible.

## 17. Acceptance Scenarios
The queue table updates within one polling interval of a backend-side status change; polling pauses when the browser tab is hidden.

## 18. Tests First
A hook test asserting the refetch interval fires correctly and pauses on visibility change.

## 19. Definition of Done
Polling behavior verified including the visibility-pause optimization.
