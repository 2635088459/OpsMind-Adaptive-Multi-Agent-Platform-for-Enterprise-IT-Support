# 04 Use Cases

## UC-TG-001：Runtime 提交 Tool Request

1. Runtime 调用 Gateway API，提交 `capabilityName`、input、reason、ticket/workflow/task refs 和 idempotency key。
2. Gateway 校验 schema、actor、capability 和 idempotency。
3. Gateway 持久化 ToolRequest。
4. Gateway 发布 `tool.request.accepted.v1` 或返回 rejected result。

## UC-TG-002：低风险只读工具自动执行

1. Gateway 计算 risk decision。
2. 如果 policy 标记为 low-risk/no-approval，ToolRequest 进入 `QUEUED`。
3. Worker claim execution attempt。
4. Connector 执行只读调用。
5. Gateway 标准化、脱敏、保存 result。
6. Gateway 发布 `tool.completed.v1`。

## UC-TG-003：高风险变更工具需要审批

1. Gateway 收到请求并识别高风险 capability。
2. Gateway 调用 06 创建 approval request 或发布 approval requested event。
3. ToolRequest 进入 `WAITING_APPROVAL`。
4. Gateway 消费 `approval.granted.v1` 后进入 `QUEUED`。
5. Gateway 消费 `approval.denied.v1` 后进入 `APPROVAL_DENIED` 并发布 `tool.completed.v1`，status 为 denied。

## UC-TG-004：connector 执行失败并重试

1. Connector 返回 retryable error 或 timeout。
2. Gateway 保存 attempt failure。
3. 根据 retry policy 创建下一次 attempt。
4. 如果达到 max attempts，ToolRequest 进入 `TERMINAL_FAILED`。
5. Gateway 发布 final `tool.completed.v1`。

## UC-TG-005：partial side effect reconciliation

1. Connector 调用超时或返回不确定状态。
2. Gateway 将 execution 标记为 `PARTIAL_SIDE_EFFECT` 或 `RECONCILING`。
3. Reconciliation worker 查询外部系统或 connector status endpoint。
4. 如果确认成功，保存 completed result。
5. 如果确认失败且可重试，创建新 attempt。
6. 如果无法确认，发布 uncertain result，Runtime/Ticket Workflow 决定人工介入。

## UC-TG-006：取消工具请求

1. Runtime 或 human operator 请求取消。
2. Gateway 校验 requester 权限和当前状态。
3. 如果尚未执行，ToolRequest 进入 `CANCELLED`。
4. 如果正在执行，进入 `CANCEL_REQUESTED` 并调用 connector cancel hook。
5. 最终发布 `tool.cancelled.v1` 或 `tool.completed.v1` with cancellation metadata。

## UC-TG-007：connector 管理员注册新工具

1. Admin 提交 connector manifest。
2. Gateway 校验 schema、capability、risk、secret requirements 和 network policy。
3. Gateway 保存 connector registry version。
4. Gateway 发布 `tool.connector.registered.v1`。
5. 新 connector 进入 `ACTIVE` 或 `DISABLED`，取决于 policy 和 health check。

## UC-TG-008：工具结果进入 Memory Knowledge

1. Gateway 完成 result normalization。
2. Gateway 保存 redacted evidence refs。
3. Gateway 发布 `tool.completed.v1`。
4. Memory Knowledge 可消费该事件或通过 result API 拉取脱敏 evidence。
5. 原始输出不直接进入 memory。

