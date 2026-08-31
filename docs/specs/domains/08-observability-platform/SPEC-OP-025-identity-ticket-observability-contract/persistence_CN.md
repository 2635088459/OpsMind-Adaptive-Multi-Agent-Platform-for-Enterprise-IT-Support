# Persistence and Deployment — SPEC-OP-025

> Domain: 08-observability-platform
> Phase: `phase-06-cross-domain-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

本规格的具体目标：锁定认证失败/step-up/越权与工单 volume/SLA/MTTR/reopen 的低基数 signals 和 trace fields。

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
