# <AlertName>

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-024
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: alert history 90d
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-024-traceability.md

## Impact

What breaks for users / the business when this fires. State whether the business
request path is affected (usually: no — observability only).

## Detection

- Firing expression: `<promql>`
- Dashboard: `<link to dashboards/...>`
- Correlation entry point: `<Explore query: metrics ↔ logs ↔ traces>`

## Triage

Ordered checks to classify the cause (saturation vs. dependency vs. config).

## Mitigation

Fast actions to stop the bleeding. If a step touches a business domain, route it
through domain-06 approval + domain-05 tool gateway — never a direct write from here.

## Resolution

Durable fix.

## Rollback

Exact revert (matches the `rollback:` header).

## Escalation

Who to page and when (owner → secondary → domain owner).

## Post-incident

Link the traceability entry; note residual risk and any follow-up spec.
