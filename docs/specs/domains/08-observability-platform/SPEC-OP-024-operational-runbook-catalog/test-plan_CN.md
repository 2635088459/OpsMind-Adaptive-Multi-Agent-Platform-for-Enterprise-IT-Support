# Test Plan — SPEC-OP-024

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：每条 page alert 必须链接有 owner、症状、dashboard、查询、止损、恢复、验证和 escalation 的 runbook。

1. Lint YAML/Jsonnet/Helm/Compose/Kubernetes and component config.
2. Run schema plus signal-contract tests against Java and Python fixtures.
3. Validate PromQL with promtool and execute relevant LogQL/TraceQL/dashboard queries.
4. Start isolated Compose/Testcontainers topology and assert ingestion, query, correlation and auth.
5. Run load/cardinality/queue/storage tests and secret/PII leak scans.
6. Inject component/network/disk failure; verify bounded loss, metrics, alerts, recovery and rollback.
7. Save commands, versions, screenshots/query outputs and pass/fail evidence in traceability.
