# 08 Transaction And Outbox

## 事务原则

Gateway 的所有状态迁移必须遵循：

1. 先持久化本域事实；
2. 同一事务写 audit record；
3. 同一事务写 outbox event；
4. 事务提交后由 outbox publisher 异步发布事件。

禁止在未提交本域状态前发布 `tool.completed.v1`。

## 创建 Tool Request

同一事务内：

1. 查询/插入 idempotency record。
2. 插入 `tool_requests`。
3. 插入 `tool_audit_records`。
4. 插入 `outbox_events(tool.request.accepted.v1)`。

如果 idempotency key 已存在且 payload hash 相同，直接返回已有 request，不创建新事件。

## Policy / Approval 决策

如果不需要审批：

1. 更新 ToolRequest 为 `APPROVED` 或 `QUEUED`。
2. 写 audit。
3. 写 outbox。

如果需要审批：

1. 保存 `approval_request_id` linkage。
2. 更新 ToolRequest 为 `WAITING_APPROVAL`。
3. 写 audit。
4. 写 `tool.approval.required.v1`。

## Worker Claim

Worker 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 或等价机制 claim 可执行 request。

同一事务内：

1. 创建或更新 `tool_executions` attempt 为 `CLAIMED`。
2. 设置 lease owner 和 lease expiry。
3. ToolRequest 进入 `EXECUTING`。
4. 写 audit。
5. 写 `tool.execution.started.v1` outbox。

## Connector Invocation

外部 connector 调用不能放在数据库事务内。

调用前必须已经持久化：

- execution attempt
- operation key
- connector id/version
- credential binding ref
- lease

调用后新事务保存结果。

## 完成执行

同一事务内：

1. 插入 `tool_results`。
2. 更新 `tool_executions` 为 final status。
3. 更新 `tool_requests` 为 final status。
4. 写 audit。
5. 写 `tool.completed.v1` outbox。

## Outbox Publisher

Publisher 必须：

- 按 `available_at` 拉取 pending events；
- 使用 broker publish confirm；
- 成功后标记 published；
- 失败增加 attempt count；
- 超过阈值进入 dead-letter outbox 状态。

## Processed Events

消费 `approval.*`、`policy.*`、`workflow.cancelled` 时，同一事务必须先插入 `processed_events`。如果唯一键冲突，直接跳过，保证重复事件幂等。

