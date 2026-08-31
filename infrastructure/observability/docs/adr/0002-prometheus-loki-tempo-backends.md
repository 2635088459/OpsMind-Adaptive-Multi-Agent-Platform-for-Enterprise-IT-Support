# ADR-0002: Prometheus, Loki, and Tempo are the telemetry backends; no custom backend

> Status: Accepted
> Date: 2026-08-30
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context

We need durable stores and query languages for the three signal types. The project
constraint is a self-hosted, open-source stack that a single team can operate, that
integrates natively with OpenTelemetry and Grafana, and that has a well-understood
capacity and retention story.

## Decision

- **Metrics → Prometheus** (PromQL, recording rules, alert-rule evaluation).
- **Logs → Loki** (LogQL, label-indexed chunks).
- **Traces → Tempo** (TraceQL, object-store-friendly blocks, span metrics /
  service graphs).
- **Grafana** is the single query and correlation surface across all three.
- **Alertmanager** owns alert routing/dedup/silence.

No custom or reimplemented telemetry backend. No secondary metrics/log/trace database.
These stores are **not** systems of record (ADR-0004): they are sized, retained, and
compacted per `SPEC-OP-015` and may be rebuilt from scratch.

## Consequences

- Native OTLP and Grafana integration; large operational knowledge base.
- Retention and capacity are explicit per backend (`SPEC-OP-015`); the team owns disk
  sizing and compaction.
- Metrics cardinality must be actively governed — Prometheus is the sensitive resource
  (`SPEC-OP-006`).
- Cross-signal correlation depends on consistent `trace_id` / `correlation_id` and
  exemplars (`SPEC-OP-004`, `SPEC-OP-005`).
- Swapping a backend later means a new ADR plus a migration spec; the OTLP producer
  contract shields services from that.

## Alternatives considered

- **Elasticsearch / OpenSearch for logs.** Rejected: heavier to operate, higher
  storage cost, weaker Grafana-native correlation for this scope.
- **Jaeger for traces.** Viable, but Tempo's object-storage model and Grafana
  integration fit the retention/cost goals better; span-metrics generation is built
  in.
- **Thanos / Mimir / Cortex now.** Deferred: single-team scale does not yet justify
  the added components; `versions.env` and overlays keep the door open.
- **All-in-one (e.g. a single TSDB for all signals).** Rejected: no mature
  self-hosted option with equivalent query languages and ecosystem.
