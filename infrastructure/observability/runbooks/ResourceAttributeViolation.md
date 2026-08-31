# ResourceAttributeViolation

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-004
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; recreate prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-004-traceability.md

## Impact

A producer is emitting telemetry that fails the SPEC-OP-004 Resource Attribute
Convention (currently: missing `service.name`). **Observability only** — the signal is
still ingested (ADR-0004), but it lands under `service.name=unknown_service` and is
hard to attribute; dashboards and per-service SLOs for that producer are wrong or
empty.

## Detection

- Firing expression:
  `count by (service_namespace, opsmind_resource_violation) ({opsmind_resource_violation!=""}) > 0`
  for 10m (`rules/alerting/signal-conformance.yml`).
- Dashboard: **Observability Platform Self-Monitoring**.
- Explore: Tempo / Loki filter `opsmind.resource.violation != ""` → look at
  `telemetry.sdk.language`, `host.name`, `process.runtime.*`, source IP to identify
  the producer.

## Triage

1. `opsmind_resource_violation` label value says which attribute is missing
   (`missing:service.name` today).
2. `service_namespace` + `telemetry.sdk.language` + `host.name` narrow it to a
   deployable and a language.
3. Confirm against `signals/resource-attributes.md` §2 which env vars that producer
   should set.

## Mitigation

- Set the producer's `OTEL_SERVICE_NAME` (and `OTEL_RESOURCE_ATTRIBUTES` for
  `service.namespace` / `service.version` / `deployment.environment`) per
  `signals/resource-attributes.md` §7 and redeploy the producer.
- This is a **producer** fix in the owning domain — the observability side only
  reports it. Do not "fix" it by editing the Collector to invent a name.

## Resolution

Producer redeployed with the correct resource attributes; `opsmind_resource_violation`
series stop appearing; alert resolves after 10m.

## Rollback

If a bad `signal-conformance.yml` change caused a false alert: `git revert <sha>`,
`promtool check rules`, recreate Prometheus.

## Escalation

`platform-observability` opens the ticket against the owning domain team
(`service_namespace`). No paging — not a business outage.

## Post-incident

If several producers regress at once, add the resource-attribute env vars to the
shared base image / deployment template and note it in the SPEC-OP-025+ cross-domain
observability contract for that domain.
