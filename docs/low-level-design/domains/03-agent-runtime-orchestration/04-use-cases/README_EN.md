# 04 Use Cases

## UC-01 Consume ticket.created

1. Runtime event consumer receives `ticket.created`.
2. Check `processed_events` by `eventId` and `eventType`.
3. Query Ticket snapshot and confirm automation can start.
4. Create Workflow Instance in `CREATED`.
5. Write `STARTED` checkpoint.
6. Planner creates the Agent Task graph.
7. Move Workflow to `RUNNING`.
8. Publish `workflow.started` through outbox.

## UC-02 Multi-Agent Orchestration

1. Coordinator scans `READY` tasks.
2. Worker claims task with a lease.
3. Agent performs reasoning and emits structured decision/result only.
4. If a tool is needed, create Tool Request; do not call Tool directly.
5. After task completion, write `AFTER_TASK` checkpoint.
6. Coordinator unlocks downstream Agent Tasks when dependencies are satisfied.
7. Join policy aggregates multiple Agent results.

## UC-03 Consume approval.granted

1. Consumer receives `approval.granted`.
2. De-duplicate and find Workflow Instance by `approvalRequestId`.
3. Validate workflow is `WAITING_FOR_APPROVAL`.
4. Write checkpoint and restore planner context.
5. Mark related Agent Task as continuable.
6. Move Workflow back to `RUNNING`.

## UC-04 Consume tool.completed

1. Consumer receives `tool.completed`.
2. Find Tool Request by `gatewayCorrelationId` or `toolRequestId`.
3. Validate Tool Request is still waiting for result.
4. Persist tool result to Tool Request and checkpoint.
5. Update the corresponding Agent Task.
6. Publish `agent.task.completed` or create downstream tasks.

## UC-05 Consume verification.completed

1. Consumer receives `verification.completed`.
2. Find waiting Workflow by `verificationRequestId`.
3. If verification passed, Runtime can enter completion path.
4. If verification failed, create remediation task or fail according to policy.
5. Runtime publishes `workflow.completed` or `workflow.failed`.

## UC-06 Pause

1. API or event triggers pause command.
2. De-duplicate with idempotency key.
3. Workflow enters `PAUSING`.
4. Stop new task claims.
5. Write `PAUSED` checkpoint and increment `pauseGeneration`.
6. Workflow enters `PAUSED`.
7. Publish `workflow.paused` through outbox.

## UC-07 Resume

1. API or event triggers resume command.
2. De-duplicate with idempotency key.
3. Validate workflow is `PAUSED`.
4. Read `PAUSED` checkpoint.
5. Restore incomplete and uncancelled tasks to `READY` or waiting state.
6. Workflow enters `RUNNING` or the matching `WAITING_*` state.
7. Publish `workflow.resumed` through outbox.

## UC-08 Runtime Crash Recovery

1. Recovery worker scans non-terminal Workflow Instances.
2. Read latest checkpoint.
3. Replay unpublished outbox events.
4. Release expired task leases.
5. Move expired `CLAIMED/RUNNING` tasks to `READY` or `FAILED_RETRYABLE`.
6. Rebuild pending external correlations.
7. Publish `workflow.recovered` audit event.
