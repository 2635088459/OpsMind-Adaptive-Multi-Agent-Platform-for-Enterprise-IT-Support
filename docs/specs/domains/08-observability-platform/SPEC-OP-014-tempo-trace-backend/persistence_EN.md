# Persistence and Deployment — SPEC-OP-014

> Domain: 08-observability-platform
> Phase: `phase-03-telemetry-backends-retention`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Deploy Tempo pipeline, block storage, TraceQL, exemplars, compaction, and metrics generator.

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
