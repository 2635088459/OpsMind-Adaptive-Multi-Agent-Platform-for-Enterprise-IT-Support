# 12 Observability

## Logs

Structured logs must include:

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

Logs must not include secrets or sensitive raw input.

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

Trace must span:

1. downstream policy request;
2. policy version selection;
3. rule evaluation;
4. decision persistence;
5. approval lifecycle;
6. outbox publication;
7. downstream consuming approval event.

## Alerts

- high-risk approval SLA breached;
- policy evaluator failure;
- unexpected allow spike;
- deny rate abnormal;
- override rate abnormal;
- outbox backlog;
- separation-of-duties violation attempt;
- audit write failure.

