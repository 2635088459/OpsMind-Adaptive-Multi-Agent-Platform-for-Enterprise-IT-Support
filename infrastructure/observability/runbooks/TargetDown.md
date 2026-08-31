# TargetDown

> owner: platform-observability
> version: 0.1.1
> spec: SPEC-OP-002
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; recreate the affected component
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-002-traceability.md

## Impact

A Prometheus scrape target is unreachable. **Observability only** — the business
request path (domains 01–07) is unaffected (ADR-0004). Effect is reduced visibility:
missing metrics for the down `job`, and dependent panels / SLOs read stale or empty.

## Detection

- Firing expression: `up == 0` for 2m (`rules/alerting/platform-self.yml`).
- Dashboard: **Observability Platform Self-Monitoring** (`dashboards/observability-platform-self.json`).
- Correlation entry point: Grafana → Explore → Loki `{job="<job>"}` around `startsAt`;
  Tempo service graph for the same service.

## Triage

1. Which `job` / `instance`? Check the alert labels.
2. Is the container running? `docker compose -f infrastructure/docker-compose/observability-stack.yml ps`.
3. Container up but target down → check the component's health endpoint
   (`/-/healthy`, `/ready`, `:13133`) and its logs.
4. Whole stack down → this is expected during a deliberate stop; silence and move on.

## Mitigation

- Component crashed: `docker compose -f infrastructure/docker-compose/observability-stack.yml up -d --force-recreate <service>`.
- Config error after a change: `git revert` the change, recreate.
- Resource pressure: check `deploy.resources.limits` in the compose file; raise for
  local, or free host memory.

## Resolution

Fix the root cause (bad config, image regression, host resource). Confirm `up == 1`
for the job and the alert resolves.

## Rollback

`git revert <sha>` of the change that introduced the failure; recreate the component.
Telemetry gap during the outage is bounded and expected (ADR-0004).

## Escalation

`platform-observability` primary → secondary. No domain escalation: this alert never
indicates a business outage.

## Post-incident

Record the gap window and cause in the SPEC-OP-002 traceability entry (or the relevant
incident record). If the target flaps, tune `for:` or add an inhibition rule under
SPEC-OP-021.
