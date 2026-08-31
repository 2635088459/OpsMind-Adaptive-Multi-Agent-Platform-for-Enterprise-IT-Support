# Persistence and Deployment — SPEC-OP-033

> Domain: 08-observability-platform
> Phase: `phase-08-self-monitoring-recovery-degraded-mode`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：监控 accepted/refused/dropped、queue/export、WAL/TSDB、ingestion/compaction、query、notification 与 synthetic probe。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
