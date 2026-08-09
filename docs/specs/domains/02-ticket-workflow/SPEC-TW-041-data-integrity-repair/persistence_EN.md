# SPEC-TW-041 Persistence Design

## Reference Migration

`V041__data_integrity_repair.sql`

## Recommended Table

`ticket_phase10_data_integrity_repair`:

- `id`, `ticket_id`, `source_reference`;
- `decision`, `reason_code`, `reason`;
- `actor_id`, `correlation_id`, `causation_id`;
- `attempt_number`, `created_at`, `completed_at`.

## Transaction Boundary

Recovery mutation, audit record, idempotency completion, and outbox/correction record commit in one explicit transaction, or use an explicit saga/retry status record.
