# Observability Platform

Status: design in progress.

Domain 08 owns the platform telemetry pipeline, operational views, alert delivery,
SLO/error-budget calculation, telemetry governance, and observability-platform
recovery. It does not own business facts and never changes Ticket, Workflow,
Policy, Tool, Memory, Identity, or Evaluation state.

## Chosen platform

- OpenTelemetry SDKs in every Java/Python producer
- OpenTelemetry Collector as the only supported OTLP ingestion boundary
- Prometheus for metrics and recording rules
- Loki for structured logs
- Tempo for distributed traces
- Grafana for dashboards and Explore
- Alertmanager for deduplicated alert routing

No general-purpose `observability-platform-service` is required for ingestion.
A thin control-plane API may be added only for audited SLO, dashboard, alert-rule,
silence, and retention administration that cannot be expressed safely as GitOps.

## Implementation

- `SPEC-OP-001` (platform boundaries, forbidden business writes, component
  responsibility matrix, ADRs, repository layout, version pinning): implemented under
  [`infrastructure/observability/`](../../../../infrastructure/observability/README.md).
  Traceability:
  [`SPEC-OP-001-traceability.md`](../../../traceability/domains/08-observability-platform/SPEC-OP-001-traceability.md).
- `SPEC-OP-002` (local observability topology): Compose for all six components with
  ports, volumes, health checks, and CPU/memory limits —
  [`infrastructure/docker-compose/observability-stack.yml`](../../../../infrastructure/docker-compose/observability-stack.yml)
  + `infrastructure/observability/*/base/` & `*/overlays/local/`, driven by
  [`scripts/observability-stack.sh`](../../../../scripts/observability-stack.sh)
  (`smoke` = up + push a real OTLP signal + query it back). Traceability:
  [`SPEC-OP-002-traceability.md`](../../../traceability/domains/08-observability-platform/SPEC-OP-002-traceability.md).
- `SPEC-OP-003` (telemetry governance baseline): deny/allow fields, retention classes,
  cardinality budgets, schema review, exception workflow —
  [`infrastructure/observability/governance/telemetry-governance.yaml`](../../../../infrastructure/observability/governance/telemetry-governance.yaml)
  + [`docs/telemetry-governance.md`](../../../../infrastructure/observability/docs/telemetry-governance.md),
  enforced by the Collector `transform/governance` processor and
  [`scripts/validate-telemetry-governance.py`](../../../../scripts/validate-telemetry-governance.py).
  Traceability:
  [`SPEC-OP-003-traceability.md`](../../../traceability/domains/08-observability-platform/SPEC-OP-003-traceability.md).
- `SPEC-OP-004` (resource attribute convention): standard service / version / namespace
  / environment / instance / SDK / tenant / cloud-K8s resource attributes —
  [`signals/resource-attributes.md`](../../../../infrastructure/observability/signals/resource-attributes.md)
  + `.yaml` + Java/Python golden fixtures, enforced by the Collector
  `resourcedetection` + `transform/resource-contract` processors (missing
  `service.name` → `unknown_service` + `opsmind.resource.violation`, never dropped)
  and [`scripts/validate-signal-contracts.py`](../../../../scripts/validate-signal-contracts.py).
  Traceability:
  [`SPEC-OP-004-traceability.md`](../../../traceability/domains/08-observability-platform/SPEC-OP-004-traceability.md).
- `SPEC-OP-005` (HTTP/AMQP trace propagation): W3C Trace Context as the only wire
  format, RabbitMQ publish/consume header-carrier + span-kind rules, a 5-key
  non-sensitive baggage allow-list with size caps —
  [`signals/trace-propagation.md`](../../../../infrastructure/observability/signals/trace-propagation.md)
  + `.yaml` + HTTP/AMQP carrier fixtures, enforced by the Collector
  `transform/baggage-contract` (strips every `baggage.*` attribute) and
  `validate-signal-contracts.py`. Traceability:
  [`SPEC-OP-005-traceability.md`](../../../traceability/domains/08-observability-platform/SPEC-OP-005-traceability.md).
- `SPEC-OP-006` (metric naming + cardinality contract): snake_case names with a
  namespace prefix, SI base units, histogram bucket sets, per-namespace label
  allow-lists, per-service series budgets —
  [`signals/metric-naming.md`](../../../../infrastructure/observability/signals/metric-naming.md)
  + `.yaml` + metric-descriptor fixtures, enforced by the Collector
  `transform/metric-cardinality` (strips forbidden per-request label keys on the
  metrics pipeline), the `cardinality.yml` recording/alert rules
  (`MetricSeriesBudgetExceeded`, `HighCardinalityJob`, `ForbiddenMetricLabel`), and
  `validate-signal-contracts.py`. Traceability:
  [`SPEC-OP-006-traceability.md`](../../../traceability/domains/08-observability-platform/SPEC-OP-006-traceability.md).

See [中文设计](./README_CN.md) and [English design](./README_EN.md).

