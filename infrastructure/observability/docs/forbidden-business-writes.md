# Forbidden Business Writes

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Status: authoritative — violations block merge

The Observability Platform is read-only with respect to every business domain. This
document enumerates what is forbidden and how the prohibition is enforced.

## 1. Prohibited actions

| # | Forbidden | Rationale |
|---|---|---|
| F1 | Any alert, alert receiver, or webhook that calls a domain 01–07 write API | An alert is a notification, not a control action. Remediation is owned by the domain or by a human operator following a runbook. |
| F2 | Recording / alerting rules that emit to anything other than the metrics store | Rules derive series and fire notifications only. They never produce domain events. |
| F3 | Grafana, dashboard, or panel actions that `POST`/`PUT`/`PATCH`/`DELETE` a business resource | Dashboards visualize. Data-source permissions are query-only. |
| F4 | Collector exporters targeting a business database, business queue, or business HTTP write endpoint | The Collector fans telemetry to Prometheus / Loki / Tempo only. |
| F5 | Writing a dashboard-computed value (SLO %, error budget, cost estimate) back as a domain fact | Domain 07 owns evaluation results; domains 01–06 own their own facts. Domain 08 computes views, never truth. |
| F6 | Storing business records in Prometheus / Loki / Tempo as a system of record | Telemetry stores are disposable. Losing them must not lose business data. |
| F7 | Telemetry that carries secrets, tokens, `Authorization` headers, cookies, MFA material, full prompts, raw user text, or unredacted PII | Redaction happens at or before export; the platform must never persist these. |
| F8 | Prometheus labels or trace attributes that carry user / ticket / workflow / request IDs as high-cardinality keys | Cardinality budget (`SPEC-OP-006`). IDs belong in exemplars / trace context, not label sets. |

## 2. Allowed adjacent behavior (not a business write)

- Emitting an alert notification to Alertmanager → a paging / chat receiver.
- Creating a **silence** in Alertmanager (control-plane, audited).
- Opening an incident record in an external incident tool via a receiver, when that
  tool is not an OpsMind business domain.
- A thin control-plane API mutating **observability** configuration (SLOs, rules,
  retention) under domain-01 identity + audit — see
  [ADR-0005](adr/0005-thin-control-plane-api-only-when-gitops-insufficient.md).

## 3. Enforcement

| Layer | Control |
|---|---|
| Review | Every PR touching `infrastructure/observability/**` is reviewed against this list by a `platform-observability` owner (`CODEOWNERS`). |
| Static check | `scripts/validate-observability-layout.py` scans Collector exporter configs, Alertmanager receivers, and Grafana data-source definitions for write-capable targets and fails CI on a match. |
| Runtime | Grafana data sources are provisioned query-only. Collector has no exporter plugin for business systems. Backend credentials are scoped to their own store. |
| Audit | Control-plane mutations produce an immutable audit record referencing the actor (domain-01), the change, and the approval (domain-06 where required). |

## 4. If a remediation truly must be automated

Route it through the owning domain: domain 08 fires the alert, the domain (or its
runbook automation) decides and acts, and the action flows through domain-06 policy /
approval and the domain-05 tool gateway with its own audit trail. Domain 08 never
holds the credential or makes the call.
