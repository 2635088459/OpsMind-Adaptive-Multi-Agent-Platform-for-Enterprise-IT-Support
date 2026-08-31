# Platform Boundaries — Data Plane and Control Plane Ownership

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Status: authoritative
> Applies to: every Observability Platform spec (`SPEC-OP-002` … `SPEC-OP-036`)

Domain 08 provides unified logs, metrics, and traces, plus correlation, dashboards,
alerts, SLOs, error budgets, capacity, and cost visibility for OpsMind. It observes
domains 01–07 and platform infrastructure. It is never authoritative for any business
fact.

## 1. Two planes

| Plane | Purpose | Change mechanism | Authority |
|---|---|---|---|
| **Data plane** | Carry immutable telemetry from producers to backends and make it queryable | Producer SDK config + Collector pipeline config, all in Git | Source domain owns signal semantics; domain 08 owns transport and storage |
| **Control plane** | Administer dashboards, rules, SLOs, silences, retention | GitOps first; a thin audited API only where GitOps cannot express a change safely (see [ADR-0005](adr/0005-thin-control-plane-api-only-when-gitops-insufficient.md)) | Domain 08, with domain-01 identity, domain-06 approval for high-risk change, and immutable audit |

## 2. What domain 08 owns

- Telemetry intake standards (resource attributes, trace propagation, metric naming,
  structured log shape, redaction rules) — `signals/`.
- OpenTelemetry Collector pipelines — receivers, processors, exporters, sampling,
  batching, retry, backpressure — `collector/`.
- Metric, log, and trace backend configuration — `prometheus/`, `loki/`, `tempo/`.
- Recording rules and alert rules — `rules/`, `prometheus/`.
- Dashboards and Grafana provisioning — `dashboards/`, `grafana/`.
- SLO / error-budget definitions and burn-rate alerts.
- Alert routing, deduplication, grouping, and silences — `alertmanager/`.
- Telemetry retention, compaction, and redaction policy.
- Observability-platform health, self-monitoring, and recovery evidence.
- Version pinning and environment overlays for all of the above.

## 3. What domain 08 does NOT own

- **Business facts.** Ticket, Workflow, Tool, Policy, Memory, Identity, and Evaluation
  state remain owned by domains 01–07. Domain 07 remains authoritative for evaluation
  results; domain 08 only visualizes and alerts on them.
- **Business databases.** Prometheus TSDB, Loki chunks, and Tempo blocks are telemetry
  stores, never a system of record. They can be wiped and rebuilt without business
  data loss.
- **Business actions.** No alert, dashboard calculation, or recording rule writes back
  into a domain. Remediation is performed by humans or by domain-owned automation,
  never by domain 08. See [`forbidden-business-writes.md`](forbidden-business-writes.md).
- **Producer instrumentation code.** Each service instruments itself with the
  OpenTelemetry SDK. Domain 08 publishes the contract (`signals/`) and validates
  conformance; it does not edit service source.
- **Identity and approval systems.** Domain 08 consumes domain-01 identity and
  domain-06 approval; it does not implement them.

## 4. Ingestion boundary

The OpenTelemetry Collector is the **only** supported OTLP ingestion boundary for
application telemetry ([ADR-0001](adr/0001-otel-collector-sole-ingestion-boundary.md)).
Producers do not write to Prometheus, Loki, or Tempo directly. Infrastructure
exporters (PostgreSQL, RabbitMQ, node/container metrics) are scraped by Prometheus or
routed through the Collector as defined by their connector spec (`SPEC-OP-029`).

```text
Java / Python services + infrastructure exporters
                 │ OTLP gRPC (4317) / OTLP HTTP (4318)   ← the only app ingress
                 ▼
      OpenTelemetry Collector Gateway
         ├── metrics → Prometheus (remote write / scrape)
         ├── logs    → Loki
         └── traces  → Tempo
                        │
                  Grafana (query)   Alertmanager (routing)
```

## 5. Availability priority

Business availability outranks telemetry delivery
([ADR-0004](adr/0004-observability-never-mutates-business-state.md)). Every producer
SDK uses bounded queues, batching, and a `dropped telemetry` counter. Collector or
backend outage degrades observability only; it must never add latency to or block a
domain 01–07 request path. Bounded, measured loss is acceptable; unbounded blocking is
not.

## 6. Change governance

- Production dashboards, alert rules, SLOs, and retention changes are version
  controlled, reviewed via pull request, and audited.
- Every artifact declares owner, version, access policy, retention, runbook, rollback,
  and audit reference — see [`artifact-metadata-convention.md`](artifact-metadata-convention.md).
- High-risk control-plane changes (retention reduction, deletion, silence of a
  critical alert class) additionally require domain-06 approval and an immutable audit
  record.
