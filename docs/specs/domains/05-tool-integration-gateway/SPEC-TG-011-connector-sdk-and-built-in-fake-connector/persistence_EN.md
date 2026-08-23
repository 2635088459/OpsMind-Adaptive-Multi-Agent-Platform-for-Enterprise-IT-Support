# Persistence — SPEC-TG-011

## Persistence Requirements

Implementation should reuse tables defined by 05 LLD where possible:

- `tool_requests`
- `tool_executions`
- `tool_connectors`
- `tool_results`
- `credential_bindings`
- `tool_audit_records`
- `outbox_events`
- `processed_events`

## Data Rules

- Every command must persist payload hash or version condition for idempotency/conflict detection.
- Every final outcome must be recoverable from the database and able to republish outbox.
- Secret values must not be stored.
- Raw output may store only storage references and classification metadata.
- Audit records and state transitions must be written in the same transaction.
