# 09 Concurrency And Idempotency

## 幂等层级

Gateway 需要四层幂等：

1. API request 幂等：`idempotencyKey + workflowInstanceId + agentTaskId`。
2. Event consumer 幂等：`eventId + consumerName`。
3. Execution attempt 幂等：`toolRequestId + attemptNumber`。
4. Connector side effect 幂等：`connectorId + operationKey`。

## Tool Request 幂等

Runtime 重试 `POST /tool-requests` 时：

- payload hash 相同：返回已有 ToolRequest。
- payload hash 不同：返回 `IDEMPOTENCY_CONFLICT`。
- 原 request 已 final：仍返回已有 final summary，不重新执行。

## Worker 并发 Claim

Worker claim 规则：

- 只 claim `QUEUED` 或 retry due 的 request。
- 使用 row lock 或 lease compare-and-set。
- 设置 `lease_owner` 和 `lease_expires_at`。
- lease 过期后其他 worker 可以接管。
- 同一 request 同时只能有一个 active execution。

## Connector Operation Key

`operationKey` 格式建议：

```text
toolRequestId:attemptNumber:connectorId:capabilityName
```

对于目标系统原生支持幂等的 connector，直接传递 operation key。

对于不支持幂等的 connector：

- mutation 操作默认需要更高 risk；
- 必须保存 external lookup metadata；
- timeout 后必须进入 reconciliation；
- 不允许盲目重复执行高风险 mutation。

## Approval Event 幂等

重复 `approval.granted.v1`：

- 如果 ToolRequest 已 `QUEUED` / `EXECUTING` / final，直接跳过。
- 如果 approval linkage 不匹配，写 security audit 并拒绝。

重复 `approval.denied.v1`：

- 如果 request 已 final，跳过。
- 如果 request 已执行，不能回滚外部副作用，只发布 audit discrepancy。

## Outbox 幂等

Outbox event 的 `eventId` 在插入时生成并持久化。Publisher 重试必须复用同一 eventId。

Consumer 不能依赖 broker exactly-once，必须按 event id 去重。

## 并发取消

取消请求与 execution completion 可能并发发生：

- completion 先提交：cancel 返回 final completed。
- cancel 先提交且未调用 connector：request 进入 `CANCELLED`。
- cancel 先提交但 connector 已调用：进入 `CANCEL_REQUESTED`，等待 connector hook/reconciliation。

