# Persistence — SPEC-PG-019

## Persistence Requirements

Implementation should reuse tables defined by 06 LLD where possible:

- `policies`
- `policy_versions`
- `policy_decisions`
- `approval_requests`
- `approval_decisions`
- `governance_audit_records`
- `outbox_events`
- `processed_events`

## Data Rules

- Every command must persist payload/input hash or version condition.
- Every final outcome must be recoverable from database and able to republish outbox.
- Sensitive raw input is not stored by default.
- Audit records and state transitions must be written in the same transaction.
- Published policy versions and final approval decisions are immutable.
