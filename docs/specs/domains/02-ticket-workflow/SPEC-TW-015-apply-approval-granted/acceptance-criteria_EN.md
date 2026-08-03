# SPEC-TW-015 — Acceptance Criteria

- Matching current `approval.granted.v1` applies successfully.
- Ticket status is `WAITING_FOR_APPROVAL`.
- `ticketId`, `workflowId`, `actionId`, and `approvalId` match.
- `approvedAt <= expiresAt`.
- Duplicate event is idempotently ACKed without duplicate business effects.
- Wrong producer/schema invalid goes to DLQ.
- Stale event is ACKed and recorded as stale without advancing the ticket.
