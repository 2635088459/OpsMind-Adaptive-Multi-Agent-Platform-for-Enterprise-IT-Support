# Recording & Alert Rule Catalog

> Owner: `platform-observability`
> Spec: `SPEC-OP-020` (this catalog), `SPEC-OP-024` (runbook body-structure check)
> Validated by: `scripts/validate-rule-catalog.py` (every alert below has real
> `severity`/`owner` labels and `summary`/`description`/`runbook_url`/`dashboard`
> annotations, and every `runbook_url` resolves to a real file — CI-enforced, not
> just documented here). `SPEC-OP-024` extends this: every runbook an alert
> points to must actually HAVE the Impact/Detection/Triage/Mitigation/Resolution/
> Rollback/Escalation/Post-incident sections `runbooks/README.md` promises, each
> with real content — an error if the runbook backs a `severity: critical`
> ("paging") alert, a warning otherwise.

This is the human-readable index this domain's `rules/README.md` implied since
`SPEC-OP-002` but never had a companion document for. Prometheus rule files are
listed first, then Loki's own ruler rules (`SPEC-OP-013`) — a separate rule family
with the identical `{alert, labels, annotations}` shape.

## Prometheus recording + alerting rules

| File | Alert(s) | Severity | Runbook | Spec |
|---|---|---|---|---|
| `http-server.yml` | `HighRequestErrorRate`, `HighRequestLatency` | critical, warning | [HttpGoldenSignals](../runbooks/HttpGoldenSignals.md) | `SPEC-OP-020` |
| `slo-http-availability.yml` | `SloErrorBudgetLow` | warning | [SloErrorBudget](../runbooks/SloErrorBudget.md) | `SPEC-OP-022` |
| `slo-burn-rate-multiwindow.yml` | `SloFastBurnPage`, `SloSlowBurnPage`, `SloSlowBurnTicket`, `SloSlowBurnTicketLong` | critical, critical, warning, warning | [SloBurnRateAlerts](../runbooks/SloBurnRateAlerts.md) | `SPEC-OP-023` |
| `identity-ticket-business.yml` | `HighIdentityAuthorizationDenialRate`, `TicketEventDeadLettered` | warning, critical | [IdentityTicketBusinessSignals](../runbooks/IdentityTicketBusinessSignals.md) | `SPEC-OP-025` |
| `runtime-memory-business.yml` | `AgentRuntimeTaskLeaseExpiredHigh`, `MemoryEmbeddingProviderFailing` | warning, critical | [RuntimeMemoryBusinessSignals](../runbooks/RuntimeMemoryBusinessSignals.md) | `SPEC-OP-026` |
| `tool-policy-business.yml` | `ToolConnectorErrorRateHigh`, `GovernancePolicyDegradedSustained` | warning, critical | [ToolPolicyBusinessSignals](../runbooks/ToolPolicyBusinessSignals.md) | `SPEC-OP-027` |
| `evaluation-business.yml` | `EvaluationGateFailureRateHigh`, `GraderErrorRateHigh` | warning, critical | [EvaluationBusinessSignals](../runbooks/EvaluationBusinessSignals.md) | `SPEC-OP-028` |
| `db-broker-infrastructure.yml` | `PostgresConnectionPoolNearSaturation`, `RabbitmqQueueBacklogHigh` | warning, warning | [DatabaseBrokerInfrastructure](../runbooks/DatabaseBrokerInfrastructure.md) | `SPEC-OP-029` |
| `cardinality.yml` | `MetricSeriesBudgetExceeded`, `HighCardinalityJob`, `ForbiddenMetricLabel` | warning, warning, critical | [MetricCardinalityBudget](../runbooks/MetricCardinalityBudget.md) | `SPEC-OP-006` |
| `signal-conformance.yml` | `ResourceAttributeViolation` | warning | [ResourceAttributeViolation](../runbooks/ResourceAttributeViolation.md) | `SPEC-OP-004` |
| `collector-resilience.yml` | `CollectorExportFailing`, `CollectorQueueNearCapacity`, `CollectorReceiverThrottling` | warning ×3 | [CollectorBackpressure](../runbooks/CollectorBackpressure.md) | `SPEC-OP-011` |
| `prometheus-tsdb.yml` | `PrometheusTsdbWalCorruption`, `PrometheusTsdbCompactionsFailing` | critical, warning | [PrometheusTsdbCapacity](../runbooks/PrometheusTsdbCapacity.md) | `SPEC-OP-012` |
| `tempo-health.yml` | `TempoDiscardingSpans`, `TempoBlockFlushFailing`, `TempoMetricsGeneratorDroppingSpans` | warning, critical, warning | [TempoIngestHealth](../runbooks/TempoIngestHealth.md) | `SPEC-OP-014` |
| `telemetry-retention.yml` | `LokiRetentionNotRunning`, `TempoRetentionErrors`, `TempoCompactionErrors` | critical, critical, warning | [TelemetryBackupRestore](../runbooks/TelemetryBackupRestore.md) | `SPEC-OP-015` |
| `platform-self.yml` | (self-monitoring — see file) | — | [TargetDown](../runbooks/TargetDown.md) | `SPEC-OP-002` |

## Loki ruler (LogQL) alerting rules

| File (tenant) | Alert(s) | Severity | Runbook | Spec |
|---|---|---|---|---|
| `loki/rules/fake/log-quality.yaml` | `HighLogSchemaViolationRate` | warning | [StructuredLogContractViolation](../runbooks/StructuredLogContractViolation.md) | `SPEC-OP-013` |

## Deliberately alert-less runbooks

| Runbook | Why no alert |
|---|---|
| [BrokenTracePropagation](../runbooks/BrokenTracePropagation.md) | `SPEC-OP-005` §6 — no clean single-series signal exists for "propagation is broken" (a root span or a new trace on consume is often legitimate); manual-investigation only, linked from the Golden Path dashboard |
| [GoldenPathServiceOverview](../runbooks/GoldenPathServiceOverview.md), [DomainOperationalOverview](../runbooks/DomainOperationalOverview.md), [AgentLlmCostCapacity](../runbooks/AgentLlmCostCapacity.md) | dashboard-usage guides (`SPEC-OP-016`~`019`), not alert runbooks — referenced via each dashboard's own `__opsmind_meta.runbook`, not an alert's `runbook_url`. (`DatabaseBrokerInfrastructure` moved out of this row as of `SPEC-OP-029` — it now backs 2 real alerts, see the table above.) |
| [AlertRoutingAndSilencing](../runbooks/AlertRoutingAndSilencing.md) | operational guide for Alertmanager's routing tree/dedup/silence (`SPEC-OP-021`), not tied to one alert |
| [ObservabilityAccessControl](../runbooks/ObservabilityAccessControl.md) | operational guide for this domain's access-control model (`SPEC-OP-030`), not tied to one alert |

## Maintaining this catalog

Adding a new alert? Add its row here in the same PR. `scripts/validate-rule-catalog.py`
enforces the underlying invariants (every alert has the required labels/annotations,
every `runbook_url` resolves to a real file, every runbook is referenced from
somewhere) — CI fails if you get those wrong, but the table above is not itself
machine-checked; keep it honest by hand.
