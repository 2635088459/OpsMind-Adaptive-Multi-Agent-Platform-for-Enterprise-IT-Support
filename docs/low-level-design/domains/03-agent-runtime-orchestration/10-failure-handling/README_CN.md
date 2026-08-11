# 10 Failure Handling

## Runtime 崩溃后怎么恢复

Recovery worker 周期性扫描非终态 Workflow Instance：

1. 读取最新 checkpoint。
2. 检查 workflow state 与 checkpoint 是否一致。
3. 重放未发布 outbox。
4. 释放过期 task lease。
5. 对 `CLAIMED/RUNNING` 且 lease 过期的 task 做 retry 或 stale 标记。
6. 重建等待中的 Tool/Approval/Verification correlation。
7. 如果发现无法判断的副作用窗口，进入 `FAILED` 或 `WAITING_FOR_INPUT`，并发布审计事件。

## 崩溃窗口

### 事务提交前崩溃

数据库无变更。事件会重新投递，processed-event 不存在，Runtime 可重试。

### 事务提交后、outbox 发布前崩溃

outbox row 已存在。Publisher 恢复后继续发布。

### Tool Gateway 请求发出后、结果未返回

Tool Request 已持久化，Runtime 等待 `tool.completed` 或执行 reconciliation query。

### Worker 执行中崩溃

lease 过期后 task 可被重新 claim。Agent task handler 必须尽量把外部副作用放到 Tool Gateway。

## Retry Policy

- Retryable 错误：网络超时、临时资源不足、broker 短暂不可用。
- Non-retryable 错误：schema 不兼容、权限拒绝、policy deny、业务前置条件失败。
- Retry 必须有最大次数和指数退避。
- 达到上限后进入 `FAILED_FINAL` 或 workflow `FAILED`。

## Poison Event

事件无法反序列化、schema 缺字段或违反不变量时：

1. 写入 poison event 表或 dead letter。
2. 不推进 Workflow。
3. 发布 observability alert。
4. 等待人工修复后 replay。

## Compensation

Runtime 不直接补偿 Ticket state。需要补偿时：

- 发布 `workflow.failed`。
- 或向 Ticket Workflow 发出受控 command。
- 或创建人工 task。

Tool 副作用补偿必须通过 Tool Gateway 的 compensating capability。
