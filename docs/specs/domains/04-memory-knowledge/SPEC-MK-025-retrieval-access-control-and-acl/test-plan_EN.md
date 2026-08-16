# SPEC-MK-025 Test Plan

## Test Types

- Unit: domain state/rules/score/redaction.
- Application: service command success, conflict, idempotency.
- Integration: PostgreSQL repository/migration and pgvector/RabbitMQ where needed.
- Contract: 02/03 event or API compatibility.
- Security: PII/secret/ACL/classification.

## Required Scenarios

- duplicate command/event does not create duplicate state.
- invalid payload is rejected or recorded as poison.
- access denied does not leak data.
- degraded mode does not fabricate evidence.
