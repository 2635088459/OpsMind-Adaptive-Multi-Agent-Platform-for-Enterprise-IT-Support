-- SPEC-PG-011: 09-concurrency-and-idempotency §Idempotency Keys names
-- "approval command: approvalRequestId + commandIdempotencyKey" as its own
-- key distinct from the request-linkage sourceRequestId/requestHash pair
-- INV-PG-005 already governs. Backfilled as NOT NULL DEFAULT so the column
-- can be added without a data migration; new rows must always supply a real
-- key going forward (ApprovalDecision's own compact constructor enforces
-- this in code).
ALTER TABLE governance.approval_decisions
    ADD COLUMN command_idempotency_key VARCHAR(200) NOT NULL DEFAULT 'unknown';

ALTER TABLE governance.approval_decisions
    ALTER COLUMN command_idempotency_key DROP DEFAULT;
