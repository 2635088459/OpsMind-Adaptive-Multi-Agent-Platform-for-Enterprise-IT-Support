# 08 Transaction and Outbox

## 事务原则

Runtime 事务必须围绕自己的 aggregate，不跨 Ticket Workflow、Tool Gateway、Approval 或 Verification 做分布式事务。

外部通信全部通过 outbox 或外部事件回调完成。

## Start Workflow 事务

同一数据库事务内完成：

1. 插入 `processed_events`。
2. 插入 `workflow_instances`。
3. 插入初始 `checkpoints`。
4. 插入 planner 生成的 `agent_tasks`。
5. 更新 Workflow state/version。
6. 插入 `outbox_events: workflow.started.v1`。

事务提交后，由 outbox publisher 发布事件。

## Task Complete 事务

同一数据库事务内完成：

1. 校验 claim token、workflow version、pause generation。
2. 更新 Agent Task 状态和 result。
3. 插入 `AFTER_TASK` checkpoint。
4. 解锁后续 ready tasks。
5. 如果需要发布结果，插入 `agent.task.completed.v1` outbox。
6. 如果 workflow 达成终态，更新 workflow 并插入 `workflow.completed.v1`。

## Tool Request 事务

同一数据库事务内完成：

1. 校验 task 仍可发起工具请求。
2. 插入 `BEFORE_TOOL_REQUEST` checkpoint。
3. 插入 `tool_requests`。
4. 将 task 设置为 `WAITING_TOOL`。
5. 将 workflow 设置为 `WAITING_FOR_TOOL`。
6. 插入需要 Tool Gateway adapter 发送的 outbox command。

Tool Gateway 调用不能在事务内直接同步执行。

## Pause 事务

同一数据库事务内完成：

1. 检查 command idempotency。
2. 锁定 Workflow Instance。
3. 状态迁移到 `PAUSING`。
4. 禁止 READY task 被 claim。
5. 递增 `pauseGeneration`。
6. 写 `PAUSED` checkpoint。
7. 状态迁移到 `PAUSED`。
8. 写 command idempotency result。
9. 插入 `workflow.paused.v1` outbox。

## Outbox Publisher

Publisher 只处理已提交事务产生的 outbox row。

要求：

- 按 `available_at` 扫描。
- 支持 retry/backoff。
- 发布成功后标记 `published_at`。
- 发布失败过多后进入 `DEAD_LETTER`。
- 发布必须带 event id，消费者依赖 event id 去重。
