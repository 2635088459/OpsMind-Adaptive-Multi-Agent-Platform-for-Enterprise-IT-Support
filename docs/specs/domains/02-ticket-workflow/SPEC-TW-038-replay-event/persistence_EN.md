# SPEC-TW-038 Persistence Design

## Reference Migration

`V038__replay_event.sql`

## Recommended Table

`ticket_phase10_replay_event`:

- `id`, `ticket_id`, `source_reference`;
- `decision`, `reason_code`, `reason`;
- `actor_id`, `correlation_id`, `causation_id`;
- `attempt_number`, `created_at`, `completed_at`.

## Transaction Boundary

Recovery mutation, audit record, idempotency completion, and outbox/correction record commit in one explicit transaction, or use an explicit saga/retry status record.
