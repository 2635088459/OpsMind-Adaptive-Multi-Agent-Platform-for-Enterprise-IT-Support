# 14 Testing Strategy

## 测试目标

测试必须证明：

- Agent 不能绕过 Gateway 直接执行工具。
- 请求、审批、执行、结果发布全链路幂等。
- 外部副作用不会因重试重复发生。
- 凭据不会泄漏到日志、事件、结果、memory。
- Gateway/worker/broker 崩溃后能恢复。

## Unit Tests

覆盖：

- ToolRequest 状态迁移；
- Execution Attempt 状态迁移；
- idempotency conflict；
- connector selection；
- approval required decision；
- retry policy；
- result normalization；
- redaction metadata；
- audit record generation。

## Integration Tests

使用 PostgreSQL + RabbitMQ/testcontainers 覆盖：

- 创建 request 写入 outbox；
- outbox 发布事件；
- approval granted 后 queued；
- worker claim + connector fake execution；
- tool.completed 被发布；
- duplicate API request 不重复 execution；
- lease expiry 后 worker 接管；
- processed event 去重。

## Connector Contract Tests

每个 connector 必须通过 contract：

- manifest schema 有效；
- input schema validation；
- output schema normalization；
- timeout behavior；
- retryable/non-retryable error mapping；
- reconcile/cancel hook 行为；
- no secret in output/log。

## Security Tests

覆盖：

- Agent 无法读取 credential/vault ref；
- raw output API 权限不足返回 forbidden；
- redaction failure 阻止事件发布 raw content；
- network policy 拒绝未声明 endpoint；
- approval required capability 未审批不能执行。

## Recovery Tests

覆盖：

- worker 在 connector invocation 前崩溃；
- worker 在 connector invocation 后、保存结果前崩溃；
- outbox publish 成功但 ack 前崩溃；
- approval event 重复投递；
- timeout 后 reconciliation 成功/失败/uncertain。

## Cross-Domain Contract Tests

必须与 03 验证：

- `POST /tool-requests` request/response schema；
- `tool.completed.v1` payload；
- duplicate `tool.completed.v1` 不导致 AgentTask 重复完成。

必须与 04 验证：

- tool evidence refs 可被 memory ingestion 消费；
- raw output 不进入 memory。

必须与 06 验证：

- `tool.approval.required.v1`；
- `approval.granted.v1` / `approval.denied.v1`。

## Acceptance Criteria

05 LLD 进入 phase/spec 前，至少应有：

- 14 个 LLD 切面完整；
- API/event/data model 可追溯到 03/04/06；
- 每个状态机有 final 状态和失败路径；
- 每个外部副作用有幂等和恢复策略；
- 测试策略覆盖安全、恢复和跨域契约。

