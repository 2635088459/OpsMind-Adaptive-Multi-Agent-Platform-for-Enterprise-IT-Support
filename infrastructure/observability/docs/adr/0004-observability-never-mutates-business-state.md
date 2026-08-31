# ADR-0004: Observability never mutates business state; business availability outranks telemetry

> Status: Accepted
> Date: 2026-08-30
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context

Domain 08 sits across the whole platform. If it could write business state or block
the request path, an observability incident would become a business incident, and an
alert could take an unreviewed action.

## Decision

Two hard rules bind every Observability Platform spec:

### 4.1 No business writes

Domain 08 is read-only with respect to domains 01–07. No alert receiver, rule,
dashboard action, or Collector exporter writes a business resource or emits a domain
event. A dashboard-computed value (SLO %, error budget, cost) is never persisted as a
domain fact. Full enumeration and enforcement:
`docs/forbidden-business-writes.md`. Automated remediation, when needed, is routed
through the owning domain's policy/approval (domain-06) and tool gateway (domain-05);
domain 08 only raises the signal.

### 4.2 Availability priority

Business availability outranks telemetry delivery. Concretely:

- Producer SDKs use bounded in-memory queues, batch export, and a
  `otel.sdk.telemetry.dropped` (or equivalent) counter. Export failure never adds
  latency to or blocks a business request.
- The Collector uses a bounded `sending_queue` with `retry_on_failure`; on saturation
  it drops with a counter and returns fast, it does not stall receivers.
- Backend outage (Prometheus/Loki/Tempo down, disk full) degrades observability only.
  Bounded, measured loss is acceptable; unbounded blocking is a defect.
- Grafana or Alertmanager being down has no business impact; alert generation
  (Prometheus) and queuing continue.

## Consequences

- Telemetry completeness is best-effort under stress, by design. Drop metrics and
  self-monitoring (`SPEC-OP-033`, `SPEC-OP-034`) make the loss visible and bounded.
- Chaos / failure specs (`SPEC-OP-034`, `SPEC-OP-035`) must prove the request path is
  unaffected by observability outage.
- Any proposed feature that would let an alert act on a domain is rejected at review.

## Alternatives considered

- **Allow "safe" auto-remediation from alerts.** Rejected: separation of duties —
  the actor that observes must not be the actor that acts. Route through the domain.
- **Block producers when telemetry cannot be delivered (guaranteed delivery).**
  Rejected: converts an observability outage into a business outage.
