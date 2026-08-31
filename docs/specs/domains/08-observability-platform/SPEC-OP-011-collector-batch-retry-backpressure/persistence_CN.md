# Persistence and Deployment — SPEC-OP-011

> Domain: 08-observability-platform
> Phase: `phase-02-collector-intake-processing`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：配置 batch、sending_queue、retry_on_failure、WAL/file storage、限流、drop accounting 与负载预算。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
