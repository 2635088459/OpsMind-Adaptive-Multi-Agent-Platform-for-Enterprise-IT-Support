# 05 API Contracts

## API Principles

Runtime APIs are primarily for internal services, workers, and operations. They are not the entry point for Ticket state transitions. Any operation that affects the Ticket lifecycle must go through Ticket Workflow.

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

Request must include `idempotencyKey`, `reasonCode`, and `requestedBy`.

Duplicate requests must return the first pause result.

### Resume Workflow

`POST /internal/agent-runtime/workflows/{workflowInstanceId}/resume`

Request must include `idempotencyKey`, `requestedBy`, and optional `resumePolicy`.

Duplicate requests must return the first resume result.

## Agent Worker API

### Claim Task

`POST /internal/agent-runtime/tasks:claim`

Worker provides `agentRole`, `workerId`, and `maxTasks`. Service returns tasks with leases.

### Complete Task

`POST /internal/agent-runtime/tasks/{agentTaskId}/complete`

Request must include:

- `claimToken`
- `workflowVersion`
- `pauseGeneration`
- `resultPayload`
- `idempotencyKey`

If workflow is paused and generation does not match, return stale and do not write success.

## Tool Request API

Agents must not call Tool APIs. Agents may only call Runtime's Tool Request command:

`POST /internal/agent-runtime/tasks/{agentTaskId}/tool-requests`

Runtime persists the request and invokes the Tool Gateway adapter.

## Query API

- `GET /internal/agent-runtime/workflows/{workflowInstanceId}`
- `GET /internal/agent-runtime/workflows/by-ticket/{ticketId}`
- `GET /internal/agent-runtime/tasks/{agentTaskId}`
- `GET /internal/agent-runtime/workflows/{workflowInstanceId}/checkpoints/latest`

Query APIs return Runtime state, not authoritative Ticket lifecycle decisions.

## Admin API

Admin APIs are operations-only:

- retry failed task
- mark poison event quarantined
- replay outbox
- force recover workflow

Every Admin API must write audit log.
