# 13 Package and Class Design

## Package Structure

Recommended package structure:

```text
agentruntime
  application
  domain
  infrastructure
  interfaces
```

## Domain Layer

Core classes:

- `WorkflowInstance`
- `AgentTask`
- `Checkpoint`
- `ToolRequest`
- `WorkflowState`
- `AgentTaskState`
- `CheckpointType`
- `WorkflowDefinition`
- `TaskGraph`

Domain layer expresses rules only. It does not depend on database, broker, HTTP client, or Tool SDK.

## Application Layer

Services:

- `StartWorkflowService`
- `PauseWorkflowService`
- `ResumeWorkflowService`
- `ClaimAgentTaskService`
- `CompleteAgentTaskService`
- `RequestToolService`
- `ConsumeRuntimeEventService`
- `RecoverWorkflowService`
- `CoordinateAgentTasksService`

Application layer owns transaction boundaries, idempotency checks, domain method calls, and outbox writes.

## Ports

Input ports:

- `WorkflowCommandPort`
- `AgentTaskCommandPort`
- `RuntimeEventConsumerPort`
- `RecoveryPort`

Output ports:

- `WorkflowInstanceRepository`
- `AgentTaskRepository`
- `CheckpointRepository`
- `ToolRequestRepository`
- `ProcessedEventRepository`
- `OutboxRepository`
- `ToolGatewayPort`
- `TicketSnapshotPort`
- `ClockPort`

## Adapters

Infrastructure adapters:

- JPA/JDBC repositories.
- Kafka/RabbitMQ event consumer.
- Outbox publisher.
- Tool Gateway HTTP/message adapter.
- Ticket Workflow query adapter.
- Metrics/tracing adapter.

Tool Gateway adapter is Runtime's only exit to tool systems.

## Interfaces

Interface layer:

- Internal REST controller.
- Event listener.
- Worker task endpoint.
- Admin controller.

Controllers do not contain business rules. They only perform request validation, auth, and DTO mapping.

## Class Boundaries

- `WorkflowInstance` decides whether workflow state transitions are legal.
- `AgentTask` decides whether task state transitions are legal.
- `Coordinator` decides which task graph nodes are runnable.
- `Planner` creates task graph but does not call tools.
- `ToolGatewayPort` sends only persisted Tool Requests.
