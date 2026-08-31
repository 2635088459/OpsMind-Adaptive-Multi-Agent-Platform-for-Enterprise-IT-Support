# Persistence and Deployment — SPEC-OP-009

> Domain: 08-observability-platform
> Phase: `phase-02-collector-intake-processing`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Configure and test ordered memory, resource, attribute, transform, filter, routing, and redaction processors.

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
