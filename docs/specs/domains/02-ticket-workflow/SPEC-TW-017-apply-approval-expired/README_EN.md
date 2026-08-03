# SPEC-TW-017 — Apply Approval Expired

## 1. Goal

Consume trusted `approval.expired.v1` or apply local expiration evaluation, mark the matching open approval request `EXPIRED`, and move the ticket from `WAITING_FOR_APPROVAL` back to `IN_PROGRESS`.

Expired approval can never authorize execution.
