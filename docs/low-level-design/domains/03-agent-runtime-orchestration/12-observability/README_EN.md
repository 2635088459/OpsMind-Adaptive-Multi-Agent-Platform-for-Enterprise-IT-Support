# 12 Observability

## Logs

All Runtime logs must include:

- `workflowInstanceId`
- `ticketId`
- `ticketCycleId`
- `agentTaskId` if present
- `correlationId`
- `causationId`
- `workerId` if present

Logs must not output secrets, tokens, or full PII payloads.

## Metrics

Workflow metrics:

- `agent_runtime_workflow_started_total`
- `agent_runtime_workflow_completed_total`
- `agent_runtime_workflow_failed_total`
- `agent_runtime_workflow_paused_total`
- `agent_runtime_workflow_recovered_total`
- `agent_runtime_workflow_duration_seconds`

Task metrics:

- `agent_runtime_task_claimed_total`
- `agent_runtime_task_completed_total`
- `agent_runtime_task_failed_total`
- `agent_runtime_task_retry_total`
- `agent_runtime_task_lease_expired_total`
- `agent_runtime_task_duration_seconds`

Event metrics:

- `agent_runtime_event_consumed_total`
- `agent_runtime_event_duplicate_total`
- `agent_runtime_outbox_pending`
- `agent_runtime_outbox_publish_failed_total`

## Tracing

Trace must flow through:

1. consumed event
2. workflow command
3. task claim
4. agent execution
5. tool request
6. tool completed callback
7. task completion
8. published event

Trace context must not replace business idempotency keys.

## Audit Events

Audit events must be retainable long term:

- workflow transition
- task transition
- checkpoint created
- tool request created
- external event consumed
- pause/resume
- recovery decision
- admin intervention

## Alerts

Alert on:

- outbox backlog exceeds threshold.
- many task leases expire.
- workflow stuck in `WAITING_*` beyond SLA.
- poison event appears.
- recovery worker fails repeatedly.
- Tool Gateway callback missing or timed out.
