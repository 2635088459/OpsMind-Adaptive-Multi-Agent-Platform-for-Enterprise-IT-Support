# SPEC-TW-015 — Apply Approval Granted

## 1. Goal

Consume trusted `approval.granted.v1`, verify it matches the current ticket, workflow, action, approval, and expiration, and mark the open approval request `GRANTED`.

After success, the ticket returns from `WAITING_FOR_APPROVAL` to `IN_PROGRESS` and stores an authorization reference for Phase 06 Tool Execution. This SPEC does not execute tools.

## 2. Scope

Included: approval event consumer, producer/schema validation, reference matching, duplicate idempotency, stale classification, `ticket.approval-granted-applied.v1`.

Excluded: Approval Service, Tool Execution, Verification.
