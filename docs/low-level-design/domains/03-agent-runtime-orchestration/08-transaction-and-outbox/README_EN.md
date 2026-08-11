# 08 Transaction and Outbox

## Transaction Principles

Runtime transactions must be scoped to Runtime aggregates. They must not create distributed transactions across Ticket Workflow, Tool Gateway, Approval, or Verification.

All external communication goes through outbox or external event callbacks.

## Start Workflow Transaction

In the same database transaction:

1. Insert `processed_events`.
2. Insert `workflow_instances`.
3. Insert initial `checkpoints`.
4. Insert planner-generated `agent_tasks`.
5. Update Workflow state/version.
6. Insert `outbox_events: workflow.started.v1`.

After commit, outbox publisher publishes the event.

## Task Complete Transaction

In the same database transaction:

1. Validate claim token, workflow version, and pause generation.
2. Update Agent Task state and result.
3. Insert `AFTER_TASK` checkpoint.
4. Unlock downstream ready tasks.
5. If needed, insert `agent.task.completed.v1` outbox.
6. If workflow reaches terminal state, update workflow and insert `workflow.completed.v1`.

## Tool Request Transaction

In the same database transaction:

1. Validate task can still request a tool.
2. Insert `BEFORE_TOOL_REQUEST` checkpoint.
3. Insert `tool_requests`.
4. Set task to `WAITING_TOOL`.
5. Set workflow to `WAITING_FOR_TOOL`.
6. Insert outbox command to be sent by Tool Gateway adapter.

Tool Gateway call must not be executed synchronously inside the transaction.

## Pause Transaction

In the same database transaction:

1. Check command idempotency.
2. Lock Workflow Instance.
3. Move state to `PAUSING`.
4. Prevent READY tasks from being claimed.
5. Increment `pauseGeneration`.
6. Write `PAUSED` checkpoint.
7. Move state to `PAUSED`.
8. Write command idempotency result.
9. Insert `workflow.paused.v1` outbox.

## Outbox Publisher

Publisher handles only outbox rows created by committed transactions.

Requirements:

- Scan by `available_at`.
- Support retry/backoff.
- Mark `published_at` after success.
- Move to `DEAD_LETTER` after repeated failures.
- Publish with event id so consumers can de-duplicate.
