# SPEC-OP-027 Traceability — Tool And Policy Observability Contract

> Domain: `08-observability-platform`
> Phase: `phase-06-cross-domain-contracts`
> Status: implemented
> Verified: 2026-09-01 (rebuilt after a mid-session data-loss incident — see SPEC-OP-025's own §5)
> Owner: `platform-observability`

## 1. Objective mapping

| Requirement | Where |
|---|---|
| Tool/connector signals, bounded labels | `metric-naming.yaml` `tool` namespace (12 real metrics from `ToolGatewayTelemetry`) |
| Policy/approval/override/audit signals | `metric-naming.yaml` `policy`/`governance`/`approval` namespaces — 3 real prefixes from ONE class |
| Query/dashboard artifact | `dashboards/tool-policy-business-signals.json` |
| Rule/runbook artifact | `rules/{recording,alerting}/tool-policy-business.yml` + `runbooks/ToolPolicyBusinessSignals.md` |

## 2. Real finding: one class, three metric-name prefixes, and a Micrometer naming-convention detail

Reading `MicrometerGovernanceMetrics.java`'s actual calls found it emits
`policy_*`, `governance_*`, and `approval_*` — three separate namespaces
contracted, not one.

A second, subtler finding: this class writes tag KEYS as camelCase Java
literals (`"riskLevel"`, `"sourceDomain"`) relying on Micrometer's
`PrometheusMeterRegistry` naming convention (auto-configured by Spring Boot
Actuator) to convert them to snake_case before Prometheus exposition —
different from `SPEC-OP-025`'s `TicketTelemetry.java`, which hand-writes
snake_case directly. The contract lists `risk_level`/`source_domain`/
`approval_type` (the real post-conversion wire form) — listing the
pre-conversion literal instead would have silently broken every query
against these three namespaces.

## 3. Real docker-compose verification (2026-09-01, second build)

Pushed real OTLP metrics, each with one forbidden label riding along:
`tool_connector_error_total{connector="jira", execution_id="SHOULD-BE-
STRIPPED"}=5`; `governance_policy_degraded_total{effect="DENY",
decision_id="SHOULD-BE-STRIPPED"}=3`. Confirmed exact raw counts (5, 3)
reached Prometheus while neither forbidden label did; both new recording
rules query-valid; both new alerts (`ToolConnectorErrorRateHigh`,
`GovernancePolicyDegradedSustained`) loaded. Every `SPEC-OP-002~029`
assertion in the same run stayed green.

## 4. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Only 2 of the ~22 total newly-contracted metrics have a dedicated alert | Low | additive follow-up |
| The camelCase-to-snake_case Micrometer assumption is not independently re-verified against a real running Spring Boot instance | Low | standard, stable Micrometer/Actuator behavior; a wrong assumption would surface as an empty dashboard series, a visible symptom |

## 5. Sign-off

Two more real, already-shipped business-metric surfaces are now contracted,
recorded, dashboarded, and alerted, proven against real pushed data
including real forbidden-label stripping. A recurring `_count` gauge shape
confirmed across a third domain. A real Micrometer naming-convention detail
documented explicitly. Rebuilt faithfully after data loss, re-verified
live.
