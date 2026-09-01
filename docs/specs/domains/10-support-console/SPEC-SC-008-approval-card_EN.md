# SPEC-SC-008 — Approval Card

> Domain: `10-support-console` | Phase: 04 — Approval Handling | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-008`, implements `UC-SC-03`'s render half.

## 2. Objective
Render a pending `ApprovalRequest` (real, per `policy-approval-governance`, domain 06) as a detail card showing what the agent wants to do and why it needs human sign-off.

## 3. Design References
`01-domain-model` §"ApprovalCard"; `04-use-cases` UC-SC-03; domain 06's real `ApprovalRequest`/`ApprovalDecision` model.

## 4. Actor
A support agent/admin viewing a ticket with a pending approval request.

## 5. Scope
Fetching and rendering the approval request's detail: the proposed action, the policy decision it's tied to, requester context.

## 6. Non-goals
The grant/deny action itself (SPEC-SC-009).

## 7. Preconditions
A ticket has an associated pending `ApprovalRequest`.

## 8. Input
The `approvalRequestId` (or ticket-scoped lookup).

## 9. Detailed Behavior
Fetch the real approval-request detail (per domain 06's `GET /approval-requests/{id}`, added in `SPEC-PG-010`) and render as a card: what action, on what target, requested by which agent/workflow, with the `policyDecisionId` context.

## 10. Interaction State Transition
N/A — a read-only detail render; the grant/deny transition is SPEC-SC-009's concern.

## 11. Business Invariants
BI-SC (fidelity) — the card must show the real pending request, never a stale or locally-cached-as-current one, given this domain's genuine multi-agent-collaboration risk (BI-SC-005).

## 12. Idempotency Strategy
N/A — a `GET`.

## 13. Consumed/Depended-on Contracts
`GET /approval-requests/{id}` — real, already implemented (per `SPEC-PG-010`, confirmed in memory as closed).

## 14. Security
Subject to the known `ApprovalController` fine-grained-authorization gap (SPEC-SC-002 §6) — any authenticated console user can currently view this, matching real backend behavior.

## 15. Observability
`traceparent` on the fetch.

## 16. Error Scenarios
Approval request already decided by someone else by the time this card is viewed (a real race in a multi-agent-collaboration domain) — render the actual current state honestly (SPEC-SC-017 hardens this specific race further).

## 17. Acceptance Scenarios
A pending approval request renders its action, target, and requester correctly from the real backend shape.

## 18. Tests First
A component test against the real `GET /approval-requests/{id}` response shape.

## 19. Definition of Done
The card renders correctly against real contract fixtures; a live integration check confirmed once wired.
