# SPEC-MK-008 Persistence

## Persistence Requirements

- Use PostgreSQL schema `memory`.
- Include version, status, created_at, and updated_at where stateful.
- Include unique keys for idempotency.
- Include migration and repository tests.

## Related Tables

- `memory.processed_events`: consumed event deduplication.
- `memory.outbox_events`: published events.
- Add concrete business tables according to this spec domain model.
