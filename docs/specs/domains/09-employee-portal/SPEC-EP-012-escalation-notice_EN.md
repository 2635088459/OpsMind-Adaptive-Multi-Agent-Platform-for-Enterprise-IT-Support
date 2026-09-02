# SPEC-EP-012 — Escalation Notice

> Domain: `09-employee-portal` | Phase: 05 — Escalation and Ticket Status | Status: Implemented

## 1. Spec Identity
`SPEC-EP-012`, implements `UC-EP-04`'s employee-facing half.

## 2. Objective
Render an honest, specific notice when the agent cannot resolve the issue itself and has escalated to a human via the real `POST /{ticketId}/triage` endpoint (per `SPEC-ARO-041`), telling the employee what happens next.

## 3. Design References
`01-domain-model` §"EscalationNotice"; `04-use-cases` UC-EP-04; `05-api-contracts` §4.

## 4. Actor
An employee whose conversation has just been escalated.

## 5. Scope
Rendering the `EscalationNotice` message type within the conversation transcript.

## 6. Non-goals
Triggering the escalation itself (owned by `SPEC-ARO-041` on the backend, driven by the agent's own permission/confidence logic, not by any client-side decision).

## 7. Preconditions
The backend has emitted an escalation event/message on this conversation.

## 8. Input
The escalation payload: reason, resulting `ticketId`, and estimated handling note (if provided).

## 9. Detailed Behavior
On receiving an escalation-type message, render a distinct notice card (not a plain chat bubble) stating the issue is now with a human agent, linking to the ticket-status view (SPEC-EP-013).

## 10. Interaction State Transition
Turn state transitions to `IDLE` after an escalation message, per `03-state-machine` §3.1 — no further self-service action is offered on this exchange.

## 11. Business Invariants
BI-EP-004/005 — the notice must never claim the issue is resolved when it has only been handed to a human; must never fabricate a promised resolution time not actually provided by the backend.

## 12. Idempotency Strategy
N/A — a read-rendering concern; the escalation event itself is backend-idempotent per `SPEC-ARO-041`.

## 13. Consumed/Depended-on Contracts
The conversation message stream carrying an escalation-type entry (contract shape TBD alongside `SPEC-EP-005`'s message schema).

## 14. Security
No additional scope — covered by the conversation-read scope already required for the transcript.

## 15. Observability
An escalation-shown client event, useful for correlating with the backend's own escalation-rate metrics (`12-observability-and-audit` §2).

## 16. Error Scenarios
Escalation payload missing `ticketId` — render the notice without a broken link, log a client-side warning, never crash the transcript.

## 17. Acceptance Scenarios
An escalation message renders a distinct notice card with a working link to the ticket's status view.

## 18. Tests First
A component test rendering the escalation notice from a fixture payload, asserting no over-claimed language appears.

## 19. Definition of Done
The notice renders honestly from real escalation payload shapes once `SPEC-ARO-041` exists; MSW-mocked until then.
