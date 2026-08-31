# ADR-0001: OpenTelemetry Collector is the sole application ingestion boundary

> Status: Accepted
> Date: 2026-08-30
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context

OpsMind services are written in Java and Python and run across domains 01–07, with
telemetry also coming from RabbitMQ, PostgreSQL, and external connectors. We need one
place to enforce resource-attribute conventions, redaction, sampling, and cardinality
control before any signal reaches storage. Allowing producers to write directly to
Prometheus / Loki / Tempo would scatter that enforcement across every service and make
it unverifiable.

## Decision

Every application producer emits telemetry over **OTLP only** (gRPC `4317` or HTTP
`4318`) to an **OpenTelemetry Collector** gateway. The Collector is the only supported
application ingestion boundary. Producers never talk to Prometheus, Loki, or Tempo
directly.

- The Collector applies resource-attribute enforcement, redaction, sampling, batching,
  and backpressure (`SPEC-OP-004` … `SPEC-OP-011`).
- Infrastructure exporters (node/container metrics, `postgres_exporter`,
  RabbitMQ metrics) are scraped by Prometheus or routed via the Collector per their
  connector spec (`SPEC-OP-029`).
- Backend-native ingestion ports are not exposed to producers.

## Consequences

- One audited choke point for redaction and cardinality — testable in isolation.
- Producers depend only on the stable OTLP contract; backends can change without
  touching services.
- The Collector is a critical-path component for telemetry (not for business): it must
  be horizontally scalable, and producers must degrade gracefully when it is
  unavailable (see ADR-0004).
- A malformed or hostile pipeline config can drop all telemetry — mitigated by
  version control, validation in CI, and staged rollout.

## Alternatives considered

- **Direct remote-write / push from SDKs.** Rejected: redaction and cardinality
  enforcement become per-service and unverifiable; backend coupling leaks into
  services.
- **Agent (sidecar/daemonset) Collector only, no gateway.** Deferred: a per-node
  agent may be added later for host metrics/logs, but a gateway tier is still required
  for tail sampling and central policy. `SPEC-OP-008` may introduce agent + gateway.
- **Vendor SaaS ingestion endpoint.** Rejected for this project: self-hosted stack is
  a stated constraint.
