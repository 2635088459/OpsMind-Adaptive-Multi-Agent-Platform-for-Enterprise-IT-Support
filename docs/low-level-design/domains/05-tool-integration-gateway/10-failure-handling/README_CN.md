# 10 Failure Handling

## 失败分类

### Validation Failure

请求 schema、capability、actor、scope 不合法。ToolRequest 进入 `REJECTED`，不执行 connector。

### Policy / Approval Failure

Policy denied 或 approval denied。Gateway 发布 final `tool.completed.v1`，status 分别为 `POLICY_DENIED` 或 `APPROVAL_DENIED`。

### Connector Retryable Failure

网络错误、429、临时 5xx、短暂依赖不可用。按 retry policy 重新调度。

### Connector Non-Retryable Failure

权限不足、输入无效、目标资源不存在。进入 `TERMINAL_FAILED`。

### Timeout / Unknown Outcome

如果 connector 调用超时但外部系统可能已经执行，不能直接重试 mutation。必须进入 `RECONCILING`。

### Partial Side Effect

外部系统部分成功，例如创建资源成功但更新标签失败。Gateway 必须保存 partial metadata，并发布明确状态。

## Reconciliation

Reconciliation worker 使用 connector-specific status lookup：

- 通过 `operationKey` 查询外部系统；
- 查询 external resource refs；
- 比对 expected output；
- 判断 `SUCCEEDED`、`FAILED`、`UNCERTAIN`。

如果长期 `UNCERTAIN`，Gateway 发布 final uncertain result，并标记需要人工处理。

## Poison Request

以下情况进入 poison：

- 同一个 request 反复触发不可解析 connector error；
- result normalization 总是失败；
- connector manifest 与实际 output schema 不兼容；
- outbox 发布持续失败超过阈值。

Poison request 不继续自动执行，必须有 admin audit。

## Gateway 崩溃恢复

启动恢复流程：

1. 重放 pending outbox。
2. 扫描 lease expired executions。
3. 对 `INVOKING` 且 lease 过期的 execution 进入 reconciliation。
4. 对 `QUEUED` request 恢复调度。
5. 对 `WAITING_APPROVAL` request 不自动推进，等待 approval event 或超时处理。

## Connector 崩溃或不可用

- `ACTIVE` connector 连续失败后进入 `DEGRADED`。
- health check 失败超过阈值后进入 `DISABLED`。
- 已 queued 的 request 需要重新选择 connector 或 terminal failed。
- 高风险 mutation 不允许自动切换 connector，除非 policy 允许。

