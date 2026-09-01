# ObservabilityAccessControl

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-030
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-030-traceability.md

Operational guide for this domain's access-control model — not tied to one
alert (same category as `AlertRoutingAndSilencing.md`), a scope decision this
spec's own traceability doc records in full.

## Scope: what this spec builds, and what it deliberately does not

Concrete objective: *"Apply OIDC/RBAC roles to UI/endpoints and audit
queries/exports."*

- **Prometheus and Alertmanager**: native `--web.config.file` basic-auth gate
  (`exporter-toolkit`, built into both since well before this stack's pinned
  versions — no external identity provider needed). This is
  **authentication only** — neither tool has a concept of role-differentiated
  permissions for its own query API; every authenticated caller gets the
  same access.
- **Grafana**: anonymous Viewer access disabled
  (`GF_AUTH_ANONYMOUS_ENABLED: "false"`); real **role-differentiated RBAC**
  via Grafana's own built-in org-role model (Admin / Editor / Viewer) —
  proven live: a Viewer-role user can read dashboards (200) but cannot
  create/modify one (403); an Admin can (200).
- **Audit**: Grafana's own default structured request logging already
  captures every query with the caller's identity
  (`logger=context userId=... uname=... method=... path=... status=...`) —
  no new configuration was needed, this was already real, just never
  previously relied upon as this domain's query-audit trail.
- **Deliberately deferred**: real OIDC against a shared identity provider.
  No shared, persistent Keycloak instance exists anywhere in this repository
  — `infrastructure/keycloak/` is an empty placeholder directory, and every
  domain's own Keycloak usage today (including user-access-authentication-
  service's real, tested integration) is ephemeral/test-scoped only, never a
  standing shared container other domains could point at. Standing one up
  is a materially larger undertaking (a new shared platform dependency,
  comparable in scope to what domain 01 spent many specs building) and was
  explicitly scoped OUT of this spec after checking with the user rather than
  built unilaterally — see this spec's traceability doc for the full
  reasoning and the decision record.
- **Prometheus/Loki/Tempo query-level audit**: neither tool has a native
  per-query audit log in its open-source build. Grafana is the enforced
  access point for humans in this stack (its own audit log covers that
  path); direct API access to Prometheus/Loki/Tempo bypasses Grafana's audit
  trail entirely — a real, stated limitation, not silently glossed over.

## A real cascading finding: enabling auth broke 3 internal integrations

Enabling Prometheus's and Alertmanager's basic-auth gates is not limited to
external/human callers — every INTERNAL service that pushes data to either
one also needed credentials added, or its writes were silently rejected
with 401:

1. **Tempo's `metrics_generator` → Prometheus `remote_write`** (span-metrics
   + exemplars, `SPEC-OP-014`) — fixed by adding `basic_auth` under
   `tempo.yml`'s own `remote_write` block.
2. **Prometheus's own alert notifications → Alertmanager** — fixed by
   adding `basic_auth` under `prometheus.yml`'s `alerting.alertmanagers`
   entry.
3. **Loki's ruler → Alertmanager** (`SPEC-OP-013`'s LogQL alerting) — fixed
   via userinfo-in-URL (`http://admin:admin@alertmanager:9093`), the
   simplest mechanism Loki's ruler notifier supports without needing to
   guess at a nested config key.

All three were found by running the REAL smoke test after enabling auth,
not by static review — `SPEC-OP-014`'s own polling assertion failed first
(`traces_spanmetrics_calls_total` never appeared), and Tempo's own container
logs showed the exact HTTP 401 causing it.

## Impact

Not alert-linked (no `severity`/`paging` concept applies) — this is an
access-control change, not an incident-detection signal. Impact if
misconfigured: either (a) too permissive — anonymous/unauthenticated access
to metrics/alerts/dashboards, a real information-disclosure risk for
whatever this platform observes; or (b) too restrictive — a legitimate
internal integration (see above) silently stops working, which is exactly
the failure mode this spec's own verification caught three times.

## Verifying the access-control model

- Unauthenticated Prometheus query: `curl "http://localhost:9090/api/v1/query?query=up"`
  → expect `401`.
- Unauthenticated Alertmanager query: `curl "http://localhost:9093/api/v2/status"`
  → expect `401`.
- Anonymous Grafana access: `curl "http://localhost:3000/api/search"` →
  expect `401`.
- Real RBAC: create a Viewer via `POST /api/admin/users` (as `admin`), then
  confirm that user gets `200` on `GET /api/search` but `403` on
  `POST /api/dashboards/db`.
- Audit trail: `docker logs opsmind-grafana | grep uname=<the-viewer-login>`
  should show the exact query, method, path, and status.

## Rollback

`git revert <sha>` on: the 2 new `web.yml` files
(`prometheus/overlays/local/`, `alertmanager/overlays/local/`); the
`observability-stack.yml` compose edits (both `--web.config.file` flags,
both healthchecks, Grafana's `GF_AUTH_ANONYMOUS_ENABLED`); the 3 internal-
integration fixes (`tempo.yml`, `prometheus.yml`, `loki.yml`); and the
`datasources.yml` Prometheus `basicAuth` entry. Recreate all affected
containers.

## Escalation

Not alert-linked — no on-call escalation path. Access-control changes go
through the same review path as any other config change in this domain
(GitOps, `ADR-0003`).

## Post-incident

Link the traceability entry
(`docs/traceability/domains/08-observability-platform/SPEC-OP-030-traceability.md`).
Residual risk: real OIDC against a shared identity provider remains
deliberately deferred (see Scope above) — a genuine follow-up if/when a
shared platform Keycloak instance is ever stood up for other reasons.
