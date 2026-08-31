# Persistence and Deployment — SPEC-OP-017

> Domain: 08-observability-platform
> Phase: `phase-04-dashboards-correlation-analysis`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Deliver owner-specific dashboards for domains 01–07 covering lifecycle, errors, backlog, and audit.

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
