# 08 Observability and Platform Infrastructure

> Status: design started  
> Core stack: OpenTelemetry Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager

## 1. Domain objective

Domain 08 provides unified logs, metrics, traces, correlation, dashboards, alerts, SLOs, error budgets, capacity, and cost visibility. A user request must be traceable from Portal/API Gateway through domains 01–07, RabbitMQ, PostgreSQL, and external connectors.

## 2. Ownership

08 owns telemetry intake standards, Collector pipelines, metric/log/trace backend configuration, recording and alert rules, dashboards, SLO/error budgets, alert routing, telemetry retention/redaction, platform health, and recovery evidence.

08 does not own Ticket, Workflow, Tool, Policy, Memory, Identity, or Evaluation facts; it never mutates business state from an alert and never writes dashboard calculations back as domain facts. Domain 07 remains authoritative for evaluation results; 08 only visualizes and alerts on them.

## 3. Deployment boundary

```text
Java/Python services + infrastructure exporters
                  │ OTLP gRPC/HTTP
                  ▼
       OpenTelemetry Collector Gateway
          ├── metrics → Prometheus
          ├── logs    → Loki
          └── traces  → Tempo
                         │
                    Grafana/Alertmanager
```

No general-purpose business service is introduced for telemetry ingestion. A thin control-plane API is justified only when GitOps cannot safely express SLO, alert, silence, or retention administration; it then requires domain-01 authorization, domain-06 approval for high-risk changes, and immutable audit.

## 4. Mandatory principles

- Every signal carries `service.name`, `service.version`, `deployment.environment`, `trace_id`, and `correlation_id`.
- Tokens, passwords, cookies, Authorization headers, MFA secrets, full prompts, raw user text, and unredacted PII are forbidden in telemetry.
- Metric labels are low-cardinality; user/ticket/workflow IDs are never Prometheus labels.
- Trace context propagates across HTTP and RabbitMQ; consumers create linked/child spans.
- Collector/backend failure never blocks core business; SDKs use bounded queues, batching, and drop metrics.
- Alerts are actionable and include severity, owner, runbook, dashboard, and correlation-query entry point.
- Production dashboards, alerts, SLOs, and retention changes are version controlled, reviewed, and audited.

## 5. LLD work packages

The remaining design follows the standard 14 slices: domain model, business invariants, state machines, use cases, APIs, signal/event contracts, data model, transaction/config publication, concurrency/idempotency, failure handling, security, observability-of-observability, package/config design, and testing strategy.
