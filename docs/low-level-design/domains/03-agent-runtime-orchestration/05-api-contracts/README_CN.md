# 05 API Contracts

## API 原则

Runtime API 主要用于内部服务、worker 和运维，不作为 Ticket 状态变更入口。任何会影响 Ticket 生命周期的操作必须经过 Ticket Workflow。

## Workflow Command API

### Start Workflow

`POST /internal/agent-runtime/workflows`

Request:

```json
{
  "ticketId": "ticket-123",
  "ticketCycleId": "cycle-1",
  "workflowType": "ticket_triage",
  "causationEventId": "event-123",
  "idempotencyKey": "ticket.created:event-123"
}
```

Response:

```json
{
  "workflowInstanceId": "wf-123",
  "state": "RUNNING",
  "workflowVersion": 2
}
```

### Pause Workflow

`POST /internal/agent-runtime/workflows/{workflowInstanceId}/pause`

Request 必须包含 `idempotencyKey`、`reasonCode`、`requestedBy`。

重复请求必须返回第一次 pause 的结果。

### Resume Workflow

`POST /internal/agent-runtime/workflows/{workflowInstanceId}/resume`

Request 必须包含 `idempotencyKey`、`requestedBy`、可选 `resumePolicy`。

重复请求必须返回第一次 resume 的结果。

## Agent Worker API

### Claim Task

`POST /internal/agent-runtime/tasks:claim`

Worker 提供 `agentRole`、`workerId`、`maxTasks`。服务返回带 lease 的 task。

### Complete Task

`POST /internal/agent-runtime/tasks/{agentTaskId}/complete`

Request 必须包含：

- `claimToken`
- `workflowVersion`
- `pauseGeneration`
- `resultPayload`
- `idempotencyKey`

如果 workflow 已暂停且 generation 不匹配，返回 stale，不得写成功结果。

## Tool Request API

Agent 不能调用 Tool API。Agent 只能调用 Runtime 的 Tool Request command：

`POST /internal/agent-runtime/tasks/{agentTaskId}/tool-requests`

Runtime 持久化 request 后调用 Tool Gateway adapter。

## Query API

- `GET /internal/agent-runtime/workflows/{workflowInstanceId}`
- `GET /internal/agent-runtime/workflows/by-ticket/{ticketId}`
- `GET /internal/agent-runtime/tasks/{agentTaskId}`
- `GET /internal/agent-runtime/workflows/{workflowInstanceId}/checkpoints/latest`

Query API 返回 Runtime state，不返回 Ticket lifecycle state 的 authoritative decision。

## Admin API

Admin API 仅限运维：

- retry failed task
- mark poison event quarantined
- replay outbox
- force recover workflow

所有 Admin API 必须写 audit log。
