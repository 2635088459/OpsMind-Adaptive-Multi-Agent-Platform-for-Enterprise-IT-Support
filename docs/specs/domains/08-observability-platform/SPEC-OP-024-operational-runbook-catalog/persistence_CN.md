# Persistence and Deployment — SPEC-OP-024

> Domain: 08-observability-platform
> Phase: `phase-05-alerts-slos-runbooks`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：每条 page alert 必须链接有 owner、症状、dashboard、查询、止损、恢复、验证和 escalation 的 runbook。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
