# SPEC-EP-013 — Ticket Status Panel

> Domain: `09-employee-portal` | Phase: 05 — Escalation and Ticket Status | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-013`, implements `UC-EP-05`'s initial render.

## 2. Objective
Render the `TicketStatusView` side panel showing the real, already-existing ticket state (status, assignee if any, last-updated) for a conversation's escalated ticket.

## 3. Design References
`01-domain-model` §"TicketStatusView"; `04-use-cases` UC-EP-05; `05-api-contracts` §5 (real, already-built `GET /api/v1/tickets/{id}` endpoint).

## 4. Actor
An employee viewing their conversation who wants to check ticket progress.

## 5. Scope
The panel's initial fetch-and-render; live updates are SPEC-EP-014's concern.

## 6. Non-goals
Real-time push (SPEC-EP-014); any ticket-mutation affordance (view-only for the employee — mutation is the support console's domain).

## 7. Preconditions
The conversation has an associated `ticketId` (true from message one, per BI-EP behavior established since domain 03's phase-10 design).

## 8. Input
The conversation's `ticketId`.

## 9. Detailed Behavior
On panel open, fetch `GET /api/v1/tickets/{id}` (real, already implemented) and render status/assignee/last-updated; a loading skeleton while pending.

## 10. Interaction State Transition
N/A — a read-only display, not a state machine of its own.

## 11. Business Invariants
BI-EP-004 — the panel must show the real backend ticket status, never a client-guessed or optimistic one.

## 12. Idempotency Strategy
N/A — a `GET`, naturally idempotent.

## 13. Consumed/Depended-on Contracts
`GET /api/v1/tickets/{id}` — real, already implemented by `02-ticket-workflow` (confirmed live in the 2026-09-01 integration verification).

## 14. Security
Requires the employee's own `tickets:read` scope, already granted by existing auth (no new scope needed — this is the first spec in domain 09 consuming an already-live contract with zero new backend work).

## 15. Observability
`traceparent` propagated on the fetch, consistent with `12-observability-and-audit` §1.

## 16. Error Scenarios
Fetch failure → panel shows a retry affordance, never a stale/fabricated status.

## 17. Acceptance Scenarios
Opening the panel on a ticket with status `IN_PROGRESS` and an assignee renders both fields correctly from the real backend.

## 18. Tests First
A component test against the real ticket-read contract's response shape (this spec, uniquely among domain 09's specs so far, can test directly against the real shape rather than an MSW-only mock, since the backend already exists).

## 19. Definition of Done
The panel renders real ticket state correctly; covered by both a contract test and a component test.
