# 04 Use Cases

## UC-01 消费 ticket.created

1. Runtime event consumer 收到 `ticket.created`。
2. 使用 `eventId` 和 `eventType` 检查 `processed_events`。
3. 查询 Ticket 快照，确认可启动自动化。
4. 创建 Workflow Instance，状态 `CREATED`。
5. 写 `STARTED` checkpoint。
6. planner 生成 Agent Task graph。
7. Workflow 迁移到 `RUNNING`。
8. outbox 发布 `workflow.started`。

## UC-02 多 Agent 编排

1. Coordinator 扫描 `READY` task。
2. Worker 用 lease claim task。
3. Agent 执行业务推理，只产出结构化 decision/result。
4. 如果需要工具，创建 Tool Request，不直接调用 Tool。
5. Task 完成后写 `AFTER_TASK` checkpoint。
6. 依赖满足后，Coordinator 解锁后续 Agent Task。
7. Join policy 汇总多个 Agent 结果。

## UC-03 消费 approval.granted

1. Consumer 收到 `approval.granted`。
2. 去重并根据 `approvalRequestId` 找到 Workflow Instance。
3. 校验 workflow 正在 `WAITING_FOR_APPROVAL`。
4. 写 checkpoint，恢复 planner context。
5. 将相关 Agent Task 标记为可继续。
6. Workflow 迁移回 `RUNNING`。

## UC-04 消费 tool.completed

1. Consumer 收到 `tool.completed`。
2. 根据 `gatewayCorrelationId` 或 `toolRequestId` 找到 Tool Request。
3. 校验 Tool Request 状态仍等待结果。
4. 保存 tool result 到 Tool Request 和 checkpoint。
5. 更新对应 Agent Task。
6. 发布 `agent.task.completed` 或创建后续 task。

## UC-05 消费 verification.completed

1. Consumer 收到 `verification.completed`。
2. 根据 `verificationRequestId` 找到等待中的 Workflow。
3. 如果 verification pass，Runtime 可进入完成路径。
4. 如果 verification fail，根据策略创建 remediation task 或失败。
5. Runtime 发布 `workflow.completed` 或 `workflow.failed`。

## UC-06 Pause

1. API 或事件触发 pause command。
2. 使用 idempotency key 去重。
3. Workflow 进入 `PAUSING`。
4. 停止新 task claim。
5. 写 `PAUSED` checkpoint 并递增 `pauseGeneration`。
6. Workflow 进入 `PAUSED`。
7. outbox 发布 `workflow.paused`。

## UC-07 Resume

1. API 或事件触发 resume command。
2. 使用 idempotency key 去重。
3. 校验 workflow 处于 `PAUSED`。
4. 读取 `PAUSED` checkpoint。
5. 将未完成且未取消的 task 恢复为 `READY` 或等待状态。
6. Workflow 进入 `RUNNING` 或对应 `WAITING_*`。
7. outbox 发布 `workflow.resumed`。

## UC-08 Runtime 崩溃恢复

1. Recovery worker 扫描非终态 Workflow Instance。
2. 读取最新 checkpoint。
3. 重放未发布 outbox。
4. 释放过期 task lease。
5. 将 `CLAIMED/RUNNING` 且 lease 过期的 task 标记为 `READY` 或 `FAILED_RETRYABLE`。
6. 重新建立等待中的 external correlation。
7. 发布 `workflow.recovered` 审计事件。
