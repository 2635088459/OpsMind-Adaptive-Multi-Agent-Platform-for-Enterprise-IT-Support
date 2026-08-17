# 08 Transaction And Outbox

## Transaction Principles

Every Gateway state transition follows:

1. persist domain facts first;
2. write audit record in the same transaction;
3. write outbox event in the same transaction;
4. publish asynchronously through outbox publisher after commit.

Publishing `tool.completed.v1` before committing domain state is forbidden.

## Create Tool Request

In one transaction:

1. Query/insert idempotency record.
2. Insert `tool_requests`.
3. Insert `tool_audit_records`.
4. Insert `outbox_events(tool.request.accepted.v1)`.

If idempotency key already exists with the same payload hash, return existing request and do not create a new event.

## Policy / Approval Decision

If approval is not required:

1. Update ToolRequest to `APPROVED` or `QUEUED`.
2. Write audit.
3. Write outbox.

If approval is required:

1. Save `approval_request_id` linkage.
2. Update ToolRequest to `WAITING_APPROVAL`.
3. Write audit.
4. Write `tool.approval.required.v1`.

## Worker Claim

Worker claims executable requests using `SELECT ... FOR UPDATE SKIP LOCKED` or equivalent.

In one transaction:

1. Create or update `tool_executions` attempt as `CLAIMED`.
2. Set lease owner and lease expiry.
3. Move ToolRequest to `EXECUTING`.
4. Write audit.
5. Write `tool.execution.started.v1` outbox.

## Connector Invocation

External connector calls must not run inside database transactions.

Before invocation, the following must already be persisted:

- execution attempt
- operation key
- connector id/version
- credential binding ref
- lease

After invocation, persist result in a new transaction.

## Complete Execution

In one transaction:

1. Insert `tool_results`.
2. Update `tool_executions` to final status.
3. Update `tool_requests` to final status.
4. Write audit.
5. Write `tool.completed.v1` outbox.

## Outbox Publisher

Publisher must:

- fetch pending events by `available_at`;
- use broker publish confirms;
- mark published after success;
- increment attempt count after failure;
- move records beyond threshold to dead-letter outbox state.

## Processed Events

When consuming `approval.*`, `policy.*`, or `workflow.cancelled`, the transaction must insert `processed_events` first. Unique-key conflict means skip the event, guaranteeing duplicate event idempotency.

