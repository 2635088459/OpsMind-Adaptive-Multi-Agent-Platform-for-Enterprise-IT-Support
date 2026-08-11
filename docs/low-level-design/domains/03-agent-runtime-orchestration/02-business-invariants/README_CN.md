# 02 Business Invariants

## 状态所有权

- Ticket state 只属于 Ticket Workflow。
- Agent Workflow state 只属于 Agent Runtime。
- Runtime 可以读取 Ticket 快照，但不能直接写 Ticket lifecycle state。
- Runtime 如果需要推进 Ticket，只能发出明确 command 或事件，由 Ticket Workflow 判断是否合法。

## Workflow Instance 不变量

- 同一个 `ticketId + ticketCycleId + workflowType` 最多只有一个 active Workflow Instance。
- Workflow Instance 必须绑定 `definitionVersion`，恢复时不能隐式切换定义版本。
- 每次状态迁移必须递增 `workflowVersion`。
- 进入终态后不能创建新的 Agent Task，除非通过新的 ticket cycle 创建新 Workflow Instance。

## Agent Task 不变量

- Agent Task 必须属于一个 Workflow Instance。
- Agent Task 不能跨 Workflow Instance 复用。
- Task 执行前必须完成所有 `dependsOn`。
- Task 完成必须写 result payload 或明确写 failure reason。
- Task 完成事件只能发布一次。

## Tool Gateway 边界

Agent 不能直接调用 Tool，原因是：

- Runtime 需要统一做权限检查、审计、限流和重试。
- Runtime 必须在外部副作用前写 checkpoint。
- Tool result 必须通过 `tool.completed` 或 `tool.failed` 回到 Runtime。
- Ticket Workflow 需要看到受控、可追踪的工具副作用。

因此任何 Agent SDK、Agent Worker 或 Task Handler 都不能持有 Tool client。只能持有 `ToolGatewayPort` 的请求接口，且接口实现位于 Runtime adapter 层。

## Pause / Resume 幂等不变量

- Pause command 必须带 `idempotencyKey`。
- Resume command 必须带 `idempotencyKey`。
- 重复 pause 只能返回同一个 paused result，不能重复发布 `workflow.paused`。
- 重复 resume 只能返回同一个 resumed result，不能重复 claim task。
- `pauseGeneration` 每次成功 pause 时递增，task worker 在提交结果时必须校验 generation。

## Checkpoint 不变量

- 任何外部副作用前必须存在 checkpoint。
- 任何可恢复等待状态必须包含 checkpoint。
- Checkpoint payload 必须可 schema-versioned 解析。
- Checkpoint 不保存 secret。

## 多 Agent 编排不变量

- Planner 只能产出 task graph，不能直接执行工具。
- Coordinator 负责决定哪些 task 可运行。
- Worker claim 必须带 lease，lease 过期后任务可重新 claim。
- Join policy 必须显式定义：all-success、first-success、quorum 或 manual-review。

## 事件处理不变量

- 所有消费事件必须先写 `processed_events` 或在同一事务中完成去重检查。
- 所有发布事件必须写 outbox，禁止直接在业务事务里同步发布 broker message。
- 每个发布事件必须带 `workflowInstanceId`、`ticketId`、`correlationId`、`causationId`。
