# Phase 01 — Unified Signal Contracts

> Domain: Observability Platform
> Phase: 01
> Specs: `SPEC-OP-004` ～ `SPEC-OP-007`
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Implementation Plan

## 1. Objective

Implement Unified Signal Contracts across application instrumentation, platform components, deployment nodes, configuration, operations, security, and verification.

## 2. Scope and deliverables

Includes version-pinned configuration, Docker Compose/Kubernetes manifests, storage/port/resource sizing, dashboards/rules/runbooks, tests, and traceability. Excludes business-state mutation and custom reimplementation of telemetry backends.

## 3. Specs

| Order | SPEC | Name |
|---|---|---|
| 1 | `SPEC-OP-004` | Resource Attribute Convention |
| 2 | `SPEC-OP-005` | HTTP And AMQP Trace Propagation |
| 3 | `SPEC-OP-006` | Metric Naming And Cardinality Contract |
| 4 | `SPEC-OP-007` | Structured Log And Redaction Contract |

## 4. Implementation requirements

- OTLP is the application ingestion boundary; configuration is version controlled and environment-overlaid.
- Every component defines image/version, ports, health check, CPU/memory, volume, retention, backup, authentication, and failure behavior.
- Telemetry contains no secrets, raw prompts/user text, or unredacted PII; metric labels remain bounded.
- Observability failure cannot block domains 01–07; alerts never mutate business state.

## 5. Exit criteria

- All specs have bilingual contracts, persistence/configuration, acceptance criteria, tests, and traceability.
- Local deployment is reproducible and production deployment has capacity/security/recovery evidence.
- Dashboards, alerts, runbooks, and signal contracts are tested against real producers.
