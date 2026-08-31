# 03-state-machine — Configuration And Alert State Machines

> Domain: Observability Platform
> Stack: `OpenTelemetry SDK/Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, Docker Compose/Kubernetes, GitOps`
> Status: Detailed LLD

## Purpose

This section defines implementation-ready configuration and alert state machines for the telemetry data plane and operational control plane. Assets include TelemetrySource, SignalContract, CollectorPipeline, BackendTenant, DashboardDefinition, AlertRule, SloDefinition, RunbookReference, RetentionPolicy, Silence and ConfigurationRelease. Each asset has owner, environment, version, labels, lifecycle, access policy, audit reference and rollback reference.

## Mandatory design

Every design specifies concrete components, nodes, ports, volumes, resource limits, retention, authentication, deployment overlays, failure behavior, operational commands, and test evidence. Signals are immutable observations; configuration changes are versioned and audited; no alert directly mutates business state.

The detailed design covers attributes, constraints, inputs/outputs, authorization, failure paths, audit, deployment, and repeatable acceptance.

## Cross-domain boundary

08 consumes telemetry from 01–07 and infrastructure, preserves source-domain semantic ownership, and provides query/dashboard/alert evidence only.
