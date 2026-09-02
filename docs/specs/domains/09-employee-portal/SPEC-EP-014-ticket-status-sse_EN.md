# SPEC-EP-014 — Ticket Status SSE

> Domain: `09-employee-portal` | Phase: 05 — Escalation and Ticket Status | Status: Implemented

## 1. Spec Identity
`SPEC-EP-014`, implements `UC-EP-05`'s live-update half.

## 2. Objective
Keep the panel from SPEC-EP-013 current in real time via Server-Sent Events, per the frozen technology baseline's explicit SSE-not-WebSocket choice.

## 3. Design References
`01-domain-model` §"TicketStatusView"; `05-api-contracts` §5 (`GET /api/v1/tickets/{id}/events`, pending); technology-baseline §4 (SSE decision).

## 4. Actor
An employee with the ticket-status panel open.

## 5. Scope
The `useTicketStatusStream` hook: SSE connection lifecycle, event-to-state mapping, reconnection is covered separately (SPEC-EP-020 hardens it; this spec covers the basic happy-path connection).

## 6. Non-goals
Reconnection backoff/hardening details (SPEC-EP-020); the SSE endpoint's own backend implementation (pending, owned by `02-ticket-workflow`, not yet built — this is a genuinely new contract, unlike SPEC-EP-013's already-live GET).

## 7. Preconditions
The panel from SPEC-EP-013 is mounted and has successfully fetched initial state.

## 8. Input
The `ticketId`; the SSE stream's `status-changed`/`assignee-changed` events.

## 9. Detailed Behavior
Open an `EventSource` to `GET /api/v1/tickets/{id}/events` on panel mount; on each event, merge into the panel's local state; close the connection on unmount.

## 10. Interaction State Transition
A simple connected/disconnected lifecycle for the stream itself, not a business state machine.

## 11. Business Invariants
BI-EP-004 — a stream update must only ever move the displayed status toward the real backend state, never introduce a client-invented intermediate state.

## 12. Idempotency Strategy
SSE events are treated as an ordered stream of full-state snapshots (not deltas needing dedup) — a duplicate/out-of-order event is a non-issue if each carries a full current state plus a monotonic version/timestamp (contract detail to confirm once the backend endpoint is designed).

## 13. Consumed/Depended-on Contracts
`GET /api/v1/tickets/{id}/events` (pending — not yet designed on the `02-ticket-workflow` side; MSW/mock-SSE-server used for this spec's own tests).

## 14. Security
Requires the same `tickets:read` scope as SPEC-EP-013; SSE auth via the same bearer-token mechanism (browser `EventSource` cannot set custom headers — the real contract must account for this, e.g. via a short-lived query-param token, a detail flagged for the backend spec to resolve, not assumed solved here).

## 15. Observability
Connection-open/connection-drop client events, useful for correlating with SPEC-EP-020's reconnect metrics.

## 16. Error Scenarios
Stream drops — SPEC-EP-020 owns recovery; this spec's own scope is simply to not crash the panel and to fall back to the last known state.

## 17. Acceptance Scenarios
A status-changed event received while the panel is open updates the displayed status without a page refresh.

## 18. Tests First
A hook test using a mock EventSource, asserting correct state merging on each event type.

## 19. Definition of Done
The happy-path stream connection is proven against a mock SSE server; a compatibility test is added once the real backend endpoint exists, with the auth-header nuance resolved in that endpoint's own spec.
