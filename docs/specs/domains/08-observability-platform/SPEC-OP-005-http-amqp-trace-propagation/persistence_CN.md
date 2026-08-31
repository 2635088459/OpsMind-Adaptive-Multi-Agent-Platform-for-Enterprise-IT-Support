# Persistence and Deployment — SPEC-OP-005

> Domain: 08-observability-platform
> Phase: `phase-01-unified-signal-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：实现 W3C traceparent/tracestate/baggage 在 HTTP 与 RabbitMQ publish/consume 的注入提取，禁止敏感 baggage。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
