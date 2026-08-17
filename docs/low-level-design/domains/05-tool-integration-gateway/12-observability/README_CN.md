# 12 Observability

## 日志

结构化日志必须包含：

- `correlationId`
- `causationId`
- `toolRequestId`
- `executionId`
- `ticketId`
- `workflowInstanceId`
- `agentTaskId`
- `capabilityName`
- `connectorId`
- `operationKey`
- `status`

日志不得包含 secret、raw output、未脱敏 PII。

## Metrics

核心指标：

- `tool_request_created_total`
- `tool_request_completed_total{status}`
- `tool_execution_latency_seconds{connector,capability,status}`
- `tool_approval_wait_seconds{capability,riskLevel}`
- `tool_connector_error_total{connector,errorCode}`
- `tool_connector_timeout_total{connector}`
- `tool_execution_retry_total{connector,capability}`
- `tool_reconciliation_total{outcome}`
- `tool_outbox_pending_count`
- `tool_outbox_publish_failure_total`
- `tool_credential_access_total{connector,scope}`
- `tool_redaction_failure_total`

## Tracing

Trace 必须贯穿：

1. Runtime `POST /tool-requests`
2. policy/approval decision
3. worker claim
4. credential binding resolution
5. connector invocation
6. result normalization/redaction
7. outbox publish
8. Runtime consume `tool.completed.v1`

外部 connector span 不得记录 sensitive payload。

## Audit Observability

Audit query 应支持：

- 按 ticket 查询所有工具执行；
- 按 workflow 查询所有 execution attempts；
- 按 connector 查询失败和凭据使用；
- 按 actor 查询工具请求；
- 按 approval request 查询执行结果。

## 告警

需要告警：

- 高风险 connector 未审批执行；
- connector error rate 超阈值；
- timeout/uncertain outcome 增多；
- outbox pending 积压；
- approval wait 超 SLA；
- redaction failure；
- credential binding disabled 但仍被请求；
- Gateway recovery 重复失败。

## SLO

初始 SLO：

- low-risk read-only request p95 完成时间小于 10 秒。
- outbox publish p95 小于 5 秒。
- approval event consume p95 小于 5 秒。
- completed event duplicate side effect rate 为 0。

