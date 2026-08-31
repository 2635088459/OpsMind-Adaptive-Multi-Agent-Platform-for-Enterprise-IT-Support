# DomainOperationalOverview

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-017
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: n/a (view only)
> runbook: self
> rollback: git revert <sha>; re-run grafana provisioning
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-017-traceability.md

Companion runbook for the **Domain Operational Overview** dashboard
(`dashboards/domain-operational-overview.json`).

## Why one templated dashboard, not seven

Rather than 7 near-duplicate per-domain JSON files (one each for
user-access-authentication, ticket-workflow, agent-runtime, memory-knowledge,
tool-integration, policy-approval-governance, evaluation-improvement), this
dashboard uses a single `$domain` template variable
(`label_values(service_namespace)`) with Grafana's own variable picker. Every
panel scopes to the selected domain. This is more maintainable (one file, one
place to fix a query) and is exactly what Grafana templating exists for — a new
domain automatically appears in the picker the moment it emits conformant
resource attributes, with zero dashboard-file change.

## What each row shows

- **Lifecycle**: request/operation rate and error/failure-outcome rate, unioned
  across every namespace a domain might emit (`http`/`db`/`amqp`/`agent`/
  `evaluation` — `SPEC-OP-006`). Only the namespaces that domain actually uses
  produce series.
- **Backlog & throughput**: AMQP publish vs. consume rate (a sustained gap is a
  growing backlog) and DB operation p95 latency.
- **Audit**: recent logs for the domain, and specifically any carrying
  `opsmind.log.violation` (`SPEC-OP-007`) — this domain's own structured-log
  contract compliance at a glance.

## Data provenance

Metric queries follow the same canonical contract as `SPEC-OP-016`. The log panels
require `service.namespace` to reach Loki as a resource attribute on the log
record (an OTLP resource attribute, not something Loki indexes by default —
`SPEC-OP-013` confirmed only `service_name`/`deployment_environment` are indexed
today, but `service_namespace` is still queryable as structured metadata via the
`| service_namespace = "..."` filter used here). See the traceability doc for
which panels were live-verified this spec vs. contract-correct pending a real
domain producer.

## Rollback

`git revert` the dashboard/runbook change; re-run Grafana provisioning.
