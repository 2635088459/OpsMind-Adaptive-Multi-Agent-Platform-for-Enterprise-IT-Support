# 10 Failure Handling

## How Runtime Recovers After Crash

Recovery worker periodically scans non-terminal Workflow Instances:

1. Read latest checkpoint.
2. Check consistency between workflow state and checkpoint.
3. Replay unpublished outbox.
4. Release expired task leases.
5. Retry or mark stale `CLAIMED/RUNNING` tasks whose lease expired.
6. Rebuild pending Tool/Approval/Verification correlations.
7. If the side-effect window cannot be determined, enter `FAILED` or `WAITING_FOR_INPUT` and publish an audit event.

## Crash Windows

### Crash Before Transaction Commit

No database changes exist. Event will be redelivered, processed-event is absent, and Runtime can retry.

### Crash After Commit but Before Outbox Publish

Outbox row exists. Publisher continues after recovery.

### Tool Gateway Request Sent but Result Not Returned

Tool Request is persisted. Runtime waits for `tool.completed` or performs reconciliation query.

### Worker Crashes During Execution

After lease expiry, task can be claimed again. Agent task handlers should place external side effects behind Tool Gateway.

## Retry Policy

- Retryable errors: network timeout, temporary resource exhaustion, transient broker unavailable.
- Non-retryable errors: incompatible schema, permission denied, policy denial, failed business precondition.
- Retry must have max attempts and exponential backoff.
- After reaching limit, move to `FAILED_FINAL` or workflow `FAILED`.

## Poison Event

When an event cannot be deserialized, misses required schema fields, or violates invariants:

1. Write to poison event table or dead letter.
2. Do not advance Workflow.
3. Publish observability alert.
4. Wait for manual fix and replay.

## Compensation

Runtime does not directly compensate Ticket state. When compensation is needed:

- Publish `workflow.failed`.
- Or send a controlled command to Ticket Workflow.
- Or create a human task.

Tool side-effect compensation must go through Tool Gateway compensating capability.
