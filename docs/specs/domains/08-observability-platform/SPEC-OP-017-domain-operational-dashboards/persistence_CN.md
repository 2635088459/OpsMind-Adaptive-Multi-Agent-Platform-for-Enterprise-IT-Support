# Persistence and Deployment — SPEC-OP-017

> Domain: 08-observability-platform
> Phase: `phase-04-dashboards-correlation-analysis`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：为 01–07 交付 owner 明确的 domain dashboard，覆盖各域核心 lifecycle、错误、积压和审计。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
