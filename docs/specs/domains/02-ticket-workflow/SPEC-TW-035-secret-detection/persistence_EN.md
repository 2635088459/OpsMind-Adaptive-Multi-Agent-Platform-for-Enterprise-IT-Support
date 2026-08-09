# SPEC-TW-035 Persistence Design

## Reference Migration

`V035__secret_detection_hardening.sql`

## Recommended Persistence

- Policy decisions may be written to `ticket.audit_records` or a dedicated security/audit table.
- Required audit transaction boundaries must be explicit for reads and commands.
- Secret detection never persists raw secrets.
- Step-up proof stores only proof id, method, verifiedAt, and expiresAt, not authentication material.

## Outbox

Phase 09 does not publish Ticket lifecycle outbox events by default. Cross-domain publication, if needed, must use redacted payloads.
