# Persistence and Deployment — SPEC-OP-035

> Domain: 08-observability-platform
> Phase: `phase-09-final-verification-release`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：执行 Identity/MFA 工单跨 01–07/HTTP/AMQP 的完整 trace，验证 dashboard/alert/runbook 与 chaos。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
