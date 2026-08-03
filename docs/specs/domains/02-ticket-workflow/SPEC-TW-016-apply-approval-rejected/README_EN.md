# SPEC-TW-016 — Apply Approval Rejected

## 1. Goal

Consume trusted `approval.rejected.v1`, verify it matches the current open approval request, mark the request `REJECTED`, and move the ticket from `WAITING_FOR_APPROVAL` back to `IN_PROGRESS`.

Rejected approval cannot authorize any Tool Execution.

## 2. Scope

Includes event consumer, producer/schema validation, reference matching, idempotency, stale classification, and `ticket.approval-rejected-applied.v1`. Excludes approval UI and Tool Execution.
