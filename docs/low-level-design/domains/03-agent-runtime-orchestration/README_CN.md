# Agent Runtime Orchestration LLD

## 范围

本目录定义 `03-agent-runtime-orchestration` 的低层设计。该域负责把 Ticket Workflow 产出的业务事件转化为可恢复、可暂停、可审计的 Agent 执行过程。

Agent Runtime 不拥有 Ticket 生命周期状态。Ticket state 仍由 `02-ticket-workflow` 管理；本域只维护 Agent Workflow state，用来表达自动化执行当前走到哪里、等待什么外部结果、哪些 Agent Task 已完成，以及 Runtime 崩溃后如何恢复。

## 核心回答

- Workflow Instance 是一次围绕某个 ticket/cycle 启动的自动化编排实例，不等于 Ticket，也不修改 Ticket 主状态。
- Agent Task 是 Workflow Instance 内部派发给某个 Agent 角色的最小可调度工作单元。
- Checkpoint 以结构化 JSON payload + version + cursor + checksum 存在持久化表中，并在外部副作用前后写入。
- Pause / Resume 通过 command idempotency key、workflow version、pause generation 和 outbox 去重保证幂等。
- 多 Agent 通过 planner、task queue、dependency graph、claim lease、join policy 和 coordinator 统一编排。
- Runtime 崩溃后通过 workflow state、checkpoint、pending task、event cursor、outbox replay 和 lease expiry 恢复。
- Runtime 消费 `ticket.created`、`approval.granted`、`tool.completed`、`verification.completed` 等外部事件，但必须落 processed-event 去重表。
- Runtime 发布 `workflow.started`、`workflow.paused`、`agent.task.completed` 等领域事件，必须经本域 outbox。
- Agent 不能直接调用 Tool。所有工具调用必须创建 Tool Request 并经过 Tool Gateway。
- Agent Workflow state 与 Ticket state 分离：二者用 ticket id/cycle id 关联，用事件同步事实，不能互相直接覆盖状态。

## 14 个 LLD 切面

1. `01-domain-model`：Workflow Instance、Agent Task、Checkpoint、Tool Request、Event Cursor。
2. `02-business-invariants`：跨实体不变量、Tool Gateway 边界、状态分离原则。
3. `03-state-machine`：Workflow、Task、Checkpoint 的状态机。
4. `04-use-cases`：事件驱动启动、暂停、恢复、多 Agent 编排、完成。
5. `05-api-contracts`：Runtime 内部 API、管理 API、Agent callback。
6. `06-event-contracts`：消费事件与发布事件契约。
7. `07-data-model`：表结构、索引、唯一键和保留策略。
8. `08-transaction-and-outbox`：事务边界、outbox、checkpoint 写入顺序。
9. `09-concurrency-and-idempotency`：并发 claim、幂等命令、重复事件处理。
10. `10-failure-handling`：崩溃恢复、重试、毒事件、补偿。
11. `11-security`：Agent 权限、Tool Gateway、凭据和审计。
12. `12-observability`：日志、指标、trace、审计事件。
13. `13-package-and-class-design`：代码包、服务、端口和适配器。
14. `14-testing-strategy`：单元、集成、契约、恢复和混沌测试。

## 与 Ticket Workflow 的关系

Ticket Workflow 决定业务流程是否合法；Agent Runtime Orchestration 决定自动化任务如何执行。二者通过事件和只读查询相连，不共享状态机，不共享事务边界，不让 Agent Runtime 直接推进 Ticket 状态。
