# 12 Observability

## Logs

Structured logs must include:

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

Logs must not contain secrets, raw output, or unredacted PII.

## Metrics

Core metrics:

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

Trace must span:

1. Runtime `POST /tool-requests`
2. policy/approval decision
3. worker claim
4. credential binding resolution
5. connector invocation
6. result normalization/redaction
7. outbox publish
8. Runtime consuming `tool.completed.v1`

External connector spans must not record sensitive payloads.

## Audit Observability

Audit query should support:

- all tool executions by ticket;
- all execution attempts by workflow;
- failures and credential usage by connector;
- tool requests by actor;
- execution results by approval request.

## Alerts

Alerts are required for:

- high-risk connector execution without approval;
- connector error rate above threshold;
- increased timeout/uncertain outcomes;
- outbox pending backlog;
- approval wait above SLA;
- redaction failure;
- disabled credential binding still requested;
- repeated Gateway recovery failure.

## SLO

Initial SLO:

- Low-risk read-only request p95 completion time below 10 seconds.
- Outbox publish p95 below 5 seconds.
- Approval event consume p95 below 5 seconds.
- Completed event duplicate side-effect rate is 0.

