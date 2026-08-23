# 12 Observability

## Logs

结构化日志必须包含：

- `correlationId`
- `causationId`
- `policyDecisionId`
- `approvalRequestId`
- `sourceDomain`
- `sourceRequestId`
- `ticketId`
- `workflowInstanceId`
- `riskLevel`
- `effect`
- `policyVersion`

日志不得包含 secret 或敏感原始 input。

## Metrics

- `policy_decision_total{effect,riskLevel,sourceDomain}`
- `policy_decision_latency_seconds`
- `approval_request_created_total{approvalType,riskLevel}`
- `approval_decision_total{decision,riskLevel}`
- `approval_wait_seconds{approvalType,riskLevel}`
- `approval_expired_total`
- `policy_publish_total`
- `policy_evaluation_failure_total`
- `governance_override_total`
- `governance_outbox_pending_count`

## Tracing

Trace 必须贯穿：

1. downstream policy request；
2. policy version selection；
3. rule evaluation；
4. decision persistence；
5. approval lifecycle；
6. outbox publication；
7. downstream consume approval event。

## 告警

- high-risk approval SLA breached；
- policy evaluator failure；
- unexpected allow spike；
- deny rate abnormal；
- override rate abnormal；
- outbox backlog；
- separation-of-duties violation attempt；
- audit write failure。

