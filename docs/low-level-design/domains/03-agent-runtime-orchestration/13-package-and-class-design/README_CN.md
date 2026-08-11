# 13 Package and Class Design

## 包结构

建议包结构：

```text
agentruntime
  application
  domain
  infrastructure
  interfaces
```

## Domain Layer

核心类：

- `WorkflowInstance`
- `AgentTask`
- `Checkpoint`
- `ToolRequest`
- `WorkflowState`
- `AgentTaskState`
- `CheckpointType`
- `WorkflowDefinition`
- `TaskGraph`

Domain layer 只表达规则，不依赖数据库、broker、HTTP client 或 Tool SDK。

## Application Layer

服务：

- `StartWorkflowService`
- `PauseWorkflowService`
- `ResumeWorkflowService`
- `ClaimAgentTaskService`
- `CompleteAgentTaskService`
- `RequestToolService`
- `ConsumeRuntimeEventService`
- `RecoverWorkflowService`
- `CoordinateAgentTasksService`

Application layer 负责事务边界、幂等检查、domain 方法调用和 outbox 写入。

## Ports

输入端口：

- `WorkflowCommandPort`
- `AgentTaskCommandPort`
- `RuntimeEventConsumerPort`
- `RecoveryPort`

输出端口：

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

Infrastructure adapters：

- JPA/JDBC repositories。
- Kafka/RabbitMQ event consumer。
- Outbox publisher。
- Tool Gateway HTTP/message adapter。
- Ticket Workflow query adapter。
- Metrics/tracing adapter。

Tool Gateway adapter 是 Runtime 唯一可以触达工具系统的出口。

## Interfaces

接口层：

- Internal REST controller。
- Event listener。
- Worker task endpoint。
- Admin controller。

Controller 不写业务规则，只做 request validation、auth、DTO mapping。

## 类边界

- `WorkflowInstance` 决定 workflow state 迁移是否合法。
- `AgentTask` 决定 task state 迁移是否合法。
- `Coordinator` 决定 task graph 中哪些 task 可运行。
- `Planner` 生成 task graph，但不调用工具。
- `ToolGatewayPort` 只发送持久化后的 Tool Request。
