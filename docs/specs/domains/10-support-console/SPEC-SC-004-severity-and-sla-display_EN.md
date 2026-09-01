# SPEC-SC-004 — Severity and SLA Display

> Domain: `10-support-console` | Phase: 02 — Ticket Queue View | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-004`, a rendering-detail extension of SPEC-SC-003.

## 2. Objective
Give each queue row a scannable severity stripe/chip and an SLA-remaining indicator, using the real severity and SLA-deadline fields already present in the ticket-list contract.

## 3. Design References
`01-domain-model` §"TicketQueueRow"; domain 02's own severity/SLA field definitions (real, already implemented).

## 4. Actor
A support agent scanning the queue for what needs attention first.

## 5. Scope
Semantic-color severity treatment (distinct from the console's own accent hue, per the artifact-design UI principle already applied in the mockup); an SLA countdown/overdue indicator computed from the real deadline field.

## 6. Non-goals
Any new backend severity/SLA computation — this spec only visualizes fields the backend already provides.

## 7. Preconditions
SPEC-SC-003's queue table is rendering.

## 8. Input
Each row's real `severity` and `slaDeadline` fields.

## 9. Detailed Behavior
Render a severity chip/stripe (critical/high/medium/low, semantic color); compute and render time-remaining-until-SLA-breach, switching to an overdue visual state once the deadline passes.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
BI-SC-002 (per domain 10's LLD, severity/SLA display accuracy) — the countdown must be computed from the real deadline, never a client-invented estimate.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
Same as SPEC-SC-003 — no new endpoint, only new fields from the existing response already being consumed.

## 14. Security
N/A — no new scope beyond SPEC-SC-003.

## 15. Observability
N/A.

## 16. Error Scenarios
A missing/null SLA deadline on a row → omit the countdown gracefully, never render a broken/negative countdown.

## 17. Acceptance Scenarios
A ticket 10 minutes from SLA breach shows an urgent countdown; a ticket past its deadline shows an overdue state.

## 18. Tests First
A component test for each severity level and each SLA state (comfortable/urgent/overdue/missing).

## 19. Definition of Done
All severity levels and SLA states render correctly and distinctly from fixture data matching the real contract shape.
