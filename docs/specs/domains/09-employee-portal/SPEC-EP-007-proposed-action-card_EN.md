# SPEC-EP-007 — Proposed Action Card

> Domain: `09-employee-portal` | Phase: 03 — Self-service Action Confirmation | Status: Implemented

## 1. Spec Identity
`SPEC-EP-007`.

## 2. Objective
Render a `ProposedAction` as the `ProposedActionCard` component: the full, untruncated explanation and confirm/decline buttons.

## 3. Design References
`01-domain-model` §"ProposedAction"; `02-business-invariants` BI-EP-007; `13-package-and-class-design` §5.

## 4. Actor
An employee who has just received a `ProposedAction` response.

## 5. Scope
The `ProposedActionCard` component only — its own rendering and forwarding of click events to SPEC-EP-008/009's hooks.

## 6. Non-goals
The actual confirm/decline network call (SPEC-EP-008/009).

## 7. Preconditions
Turn state is `AWAITING_CONFIRMATION`.

## 8. Input
A `ProposedAction` object.

## 9. Detailed Behavior
Renders `summary` in full (BI-EP-007: no CSS truncation, no ellipsis, regardless of viewport width) plus Confirm/Decline buttons.

## 10. Interaction State Transition
Presentational only; transitions themselves belong to SPEC-EP-006's store, triggered by SPEC-EP-008/009.

## 11. Business Invariants
BI-EP-007 (this spec's own reason for existing) — directly enforced by a component test asserting no `text-overflow: ellipsis`/`overflow: hidden` is applied to `summary`.

## 12. Idempotency Strategy
N/A — pure rendering.

## 13. Consumed/Depended-on Contracts
None directly — consumes the `ProposedAction` shape already returned by SPEC-EP-005.

## 14. Security
Renders `summary` as plain text/restricted Markdown only, never raw HTML (`11-security-and-authorization` §4).

## 15. Observability
N/A.

## 16. Error Scenarios
N/A — a malformed `ProposedAction` (missing `summary`) is a contract violation caught by SPEC-EP-005's own contract tests, not handled here.

## 17. Acceptance Scenarios
A `summary` of realistic length (matching the mockup's own copy) renders in full at common viewport widths, including mobile.

## 18. Tests First
A component test asserting no truncation styling and that both buttons forward the correct `actionId`.

## 19. Definition of Done
BI-EP-007 is verified by an automated test, not just visual inspection.
