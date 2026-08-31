# Test Plan — SPEC-OP-019

> Domain: 08-observability-platform
> Phase: `phase-04-dashboards-correlation-analysis`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：展示 LLM provider/model/prompt version 的 token、latency、cost、budget、agent loop、worker capacity，禁用高基数。

1. Lint YAML/Jsonnet/Helm/Compose/Kubernetes and component config.
2. Run schema plus signal-contract tests against Java and Python fixtures.
3. Validate PromQL with promtool and execute relevant LogQL/TraceQL/dashboard queries.
4. Start isolated Compose/Testcontainers topology and assert ingestion, query, correlation and auth.
5. Run load/cardinality/queue/storage tests and secret/PII leak scans.
6. Inject component/network/disk failure; verify bounded loss, metrics, alerts, recovery and rollback.
7. Save commands, versions, screenshots/query outputs and pass/fail evidence in traceability.
