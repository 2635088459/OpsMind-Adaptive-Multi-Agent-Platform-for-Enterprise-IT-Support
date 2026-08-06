# SPEC-TW-029 Acceptance Criteria

## Functional Acceptance

- Given the Ticket is in an allowed state, submitting `cancelTicket` applies the `CANCELLED` outcome.
- `ticket.cancelled.v1` is published only after the state or ownership mutation commits.
- The response returns ticketId, state, workflowVersion, auditId, and eventId.

## Idempotency and Concurrency

- Reusing the same idempotency key returns the first successful result.
- Concurrent commands against the same Ticket allow only one version compare-and-swap to succeed.
- Stale expectedVersion returns `409 CONFLICT`.

## Security and Audit

- Unauthorized actors receive `403 FORBIDDEN`.
- Missing reason returns `400 BAD_REQUEST`.
- Rejected paths write command rejection metrics and do not publish success events.
