# 12 Observability

## 日志

所有 Runtime 日志必须带：

- `workflowInstanceId`
- `ticketId`
- `ticketCycleId`
- `agentTaskId` 如果存在
- `correlationId`
- `causationId`
- `workerId` 如果存在

日志不输出 secret、token、完整 PII payload。

## Metrics

Workflow metrics：

- `agent_runtime_workflow_started_total`
- `agent_runtime_workflow_completed_total`
- `agent_runtime_workflow_failed_total`
- `agent_runtime_workflow_paused_total`
- `agent_runtime_workflow_recovered_total`
- `agent_runtime_workflow_duration_seconds`

Task metrics：

- `agent_runtime_task_claimed_total`
- `agent_runtime_task_completed_total`
- `agent_runtime_task_failed_total`
- `agent_runtime_task_retry_total`
- `agent_runtime_task_lease_expired_total`
- `agent_runtime_task_duration_seconds`

Event metrics：

- `agent_runtime_event_consumed_total`
- `agent_runtime_event_duplicate_total`
- `agent_runtime_outbox_pending`
- `agent_runtime_outbox_publish_failed_total`

## Tracing

Trace 必须贯穿：

1. consumed event
2. workflow command
3. task claim
4. agent execution
5. tool request
6. tool completed callback
7. task completion
8. published event

Trace context 不能代替业务幂等键。

## Audit Events

审计事件必须可长期保存：

- workflow transition
- task transition
- checkpoint created
- tool request created
- external event consumed
- pause/resume
- recovery decision
- admin intervention

## Alerts

需要告警：

- outbox backlog 超阈值。
- task lease 大量过期。
- workflow stuck in `WAITING_*` 超过 SLA。
- poison event 出现。
- recovery worker 多次失败。
- Tool Gateway callback 缺失或超时。
