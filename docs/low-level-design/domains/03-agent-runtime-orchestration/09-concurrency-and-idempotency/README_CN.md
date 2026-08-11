# 09 Concurrency and Idempotency

## 并发模型

Runtime 可以水平扩展多个 worker。所有并发安全必须由数据库锁、乐观版本、唯一键和幂等表共同保证。

## Task Claim

Claim 规则：

- 只允许 claim `READY` task。
- workflow 必须处于 `RUNNING`。
- `pauseGeneration` 必须写入 task claim。
- 使用 `FOR UPDATE SKIP LOCKED` 或等价机制避免多个 worker claim 同一任务。
- claim 成功后写 `claimToken` 和 `claimExpiresAt`。

Worker 完成时必须提交 `claimToken`。不匹配则拒绝。

## Workflow Version

每次 workflow state 变化递增 `workflowVersion`。

Task worker 读取 task 时拿到 `workflowVersion`，提交结果时必须校验版本或校验允许的版本范围。对于 pause/resume，必须额外校验 `pauseGeneration`。

## 消费事件幂等

每个 consumer 在处理事件前检查：

- `eventId`
- `consumerName`
- `eventType`

重复事件必须返回已处理结果，不得重新创建 task、tool request 或 outbox event。

## Command 幂等

Start、Pause、Resume、Complete Task、Request Tool 都必须带 `idempotencyKey`。

幂等记录保存：

- request hash
- response payload
- command status
- target id

相同 key 但 request hash 不同，必须返回 conflict。

## Pause / Resume 怎么保证幂等

Pause：

- 第一次成功 pause 写 `command_idempotency`。
- 重复 pause 直接返回保存的 response。
- 如果 workflow 已经 `PAUSED`，但没有相同 idempotency key，则返回当前 paused 状态，不重复发布事件。

Resume：

- 第一次成功 resume 写 `command_idempotency`。
- 重复 resume 直接返回保存的 response。
- 如果 workflow 已经 resumed，则根据 idempotency key 判断返回已保存结果或 conflict。

## 外部回调幂等

`tool.completed`、`approval.granted`、`verification.completed` 必须同时校验：

- event id 未处理。
- pending request id 匹配。
- workflow 处于对应 waiting state。
- request state 尚未 completed。

任何一个条件不满足，不得重复推进 Workflow。
