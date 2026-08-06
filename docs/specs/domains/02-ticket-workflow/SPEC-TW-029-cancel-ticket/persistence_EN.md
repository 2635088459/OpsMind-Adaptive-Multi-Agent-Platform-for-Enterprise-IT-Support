# SPEC-TW-029 Persistence Design

## Aggregate Update

- `tickets.state`: target effect `CANCELLED`.
- `tickets.workflow_version`: incremented after CAS succeeds.
- `tickets.updated_at`: command commit time.
- ownership/resolution/escalation fields are updated according to this SPEC.

## Audit Table

Reference migration: `V029__cancel_ticket.sql`.

Recommended table: `ticket_phase8_cancel_ticket_audit` with fields:

- `id`, `ticket_id`, `workflow_version`;
- `actor_id`, `reason_code`, `reason`;
- `from_state`, `to_state`;
- `idempotency_key`, `correlation_id`, `causation_id`;
- `created_at`.

## Outbox

- outbox topic/event: `ticket.cancelled.v1`.
- payload references the audit row id.
- publish failures are retried by the outbox relay.
