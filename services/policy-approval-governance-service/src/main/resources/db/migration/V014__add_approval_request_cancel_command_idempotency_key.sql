-- SPEC-PG-012: cancel commands need their own idempotency guard
-- (09-concurrency-and-idempotency, 05-api-contracts "every command must
-- include idempotency key"), distinct from the grant/deny
-- command_idempotency_key V013 added to approval_decisions — cancel never
-- creates an approval_decisions row, so the key has to live on the request
-- itself. Nullable: only ever set once a request transitions to CANCELLED.
ALTER TABLE governance.approval_requests
    ADD COLUMN cancel_command_idempotency_key VARCHAR(200);
