# Persistence and Deployment — SPEC-OP-028

> Domain: 08-observability-platform
> Phase: `phase-06-cross-domain-contracts`
> Status: planned
> Deployables: OTel Collector / Prometheus / Loki / Tempo / Grafana / Alertmanager

Concrete objective: Consume evaluation score/gate/canary/cost signals while domain 07 retains ownership.

Source configuration lives in Git; metrics use Prometheus WAL/TSDB, logs use Loki index/chunks, and traces use Tempo blocks. The implementation documents storage class/PVC or object storage, capacity formula, retention, compaction, encryption, backup/restore, deletion evidence and disk-full behavior. Telemetry stores are never authoritative business databases.
