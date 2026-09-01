# SPEC-OP-030 Traceability — Observability Access Control

> Domain: `08-observability-platform`
> Phase: `phase-07-security-privacy-config-governance` (opens this phase)
> Status: implemented
> Verified: 2026-09-01
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Apply OIDC/RBAC roles to UI/endpoints and audit
queries/exports."*

| Requirement | Where | Scope |
|---|---|---|
| Prometheus/Alertmanager access control | native `--web.config.file` basic-auth | Authentication only — neither tool has role-differentiated permissions |
| Grafana access control | `GF_AUTH_ANONYMOUS_ENABLED: "false"` + Grafana's own org-role model | Real RBAC (Admin/Editor/Viewer), proven live |
| Audit | Grafana's own default structured request logging | Already real, confirmed and documented, not newly built |
| Real OIDC | Deliberately deferred | No shared, persistent identity provider exists anywhere in this repo yet |

## 2. A real, consequential scope decision — made with the user, not assumed

Investigating this spec's real starting point found `prometheus.yml`'s own
comment (written during `SPEC-OP-012`) already anticipating this exact
question: *"Authentication: query-API/web access control is explicitly
SPEC-OP-030 ... job — adding ad-hoc basic_auth here now would be redone/
duplicated once OP-030 defines the real cross-component RBAC model."*

Checking what a real OIDC implementation would require found
`infrastructure/keycloak/` is an empty placeholder directory
(`.gitkeep` only) — no shared, persistent Keycloak instance exists anywhere
in this repository. Every domain's own Keycloak usage today, including
user-access-authentication-service's real, tested integration
(`SPEC-UA-0xx`), is ephemeral/test-scoped only (Testcontainers), never a
standing shared container other domains could point at.

Standing one up specifically for Grafana would be a materially larger
undertaking than every other spec in this domain's cross-domain-contract
phase — a new shared platform dependency other domains might later expect
to reuse, comparable in scope to what domain 01 spent many specs building.
Given the scale and the fact a wrong unilateral choice here would either
waste substantial effort or silently commit to an architecture decision
affecting more than this domain, this was surfaced to the user rather than
picked. **The user chose the lighter, native-mechanism scope.**

## 3. What was built

- **Prometheus and Alertmanager**: `--web.config.file` pointing at a new
  `overlays/local/web.yml` per component, each with one bcrypt-hashed
  `basic_auth_users` entry (`admin`, a non-secret local-dev placeholder —
  same convention as `GF_SECURITY_ADMIN_PASSWORD`/`OTEL_GATEWAY_AUTH_TOKEN`
  already committed in plaintext elsewhere in this repo). This gates the
  WHOLE web server — query API included — with no role differentiation;
  documented explicitly as authentication only.
- **Grafana**: `GF_AUTH_ANONYMOUS_ENABLED` flipped to `"false"`. Real RBAC
  proven live (§5) via Grafana's own built-in org-role model — no new
  mechanism needed, just turned on and exercised for real.
- **Grafana's Prometheus datasource**: given `basicAuth`/credentials
  (`grafana/base/provisioning/datasources/datasources.yml`) — otherwise
  every dashboard panel and alert-rule query through this datasource would
  have started failing with 401 the moment Prometheus's own gate went live.
- **Audit**: confirmed (not built) that Grafana's own default request
  logging already includes `userId`/`uname`/`method`/`path`/`status` on
  every request — this domain's real query-audit trail from now on.

## 4. Real cascading finding: enabling auth broke 3 internal integrations, plus a BusyBox wget bug

Both bugs were found via live verification, not static review, in the same
recovery pass that rebuilt `SPEC-OP-025`~`029` after a mid-session data-loss
incident. Full account (root cause, fix, and reasoning) is recorded in
`SPEC-OP-029`'s own traceability doc §4–5, since the FIRST symptom
(`SPEC-OP-014`'s exemplar assertion failing) surfaced through that spec's
own dependency chain. Summary:

1. Prometheus/Alertmanager's images ship BusyBox's minimal `wget`, which
   has no `--http-user`/`--http-password` flags — fixed with a manually-
   built `Authorization: Basic` header.
2. Enabling the auth gates silently broke Tempo's `remote_write` to
   Prometheus, Prometheus's own notifications to Alertmanager, and Loki's
   ruler notifications to Alertmanager — each needed credentials added at
   its own producing end.

## 5. Real verification (2026-09-01)

- `curl "http://localhost:9090/api/v1/query?query=up"` (no credentials) →
  `401`. With `-u admin:admin` → `200`.
- `curl "http://localhost:9093/api/v2/status"` (no credentials) → `401`.
  With `-u admin:admin` → `200`.
- `curl "http://localhost:3000/api/search"` (no credentials, anonymous) →
  `401`.
- Created a real user `obs-viewer` via `POST /api/admin/users` (as
  `admin`); confirmed via `GET /api/org/users` it received `role: "Viewer"`
  (Grafana's own org-default role), not assigned manually.
- `GET /api/search` as `obs-viewer` → `200` (can read).
  `POST /api/dashboards/db` as `obs-viewer` → `403` (cannot write).
  The SAME `POST /api/dashboards/db` as `admin` → `200` (can write) — the
  real Admin-vs-Viewer contrast, not just "auth exists."
- `docker logs opsmind-grafana | grep uname=obs-viewer` showed the real,
  already-existing structured log line: `logger=context userId=2 orgId=1
  uname=obs-viewer ... msg="Request Completed" method=POST
  path=/api/dashboards/db status=403 ...` — the real audit trail.
- Re-ran the full smoke suite a second time (idempotency check): the
  `obs-viewer` user and RBAC proof both repeated cleanly.
- Every `SPEC-OP-002~030` assertion in the same run stayed green.

## 6. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Real OIDC against a shared identity provider remains deferred | Medium (a real, stated limitation) | needs a shared platform Keycloak instance no domain has stood up yet; a genuine follow-up if one is ever built for other reasons |
| Prometheus/Loki/Tempo have no native per-query audit log in OSS | Low | Grafana is the enforced human access point and IS audited; direct API access bypasses that trail — stated plainly, not hidden |
| The local-dev `admin`/`admin` basic-auth password is a real, if non-secret, credential now checked into this repo across 2 new `web.yml` files plus 3 producer configs | Low | consistent with this repo's own existing convention for local-only credentials (never a production value) |

## 7. Sign-off

Real authentication now gates Prometheus's and Alertmanager's query APIs;
real, role-differentiated RBAC gates Grafana, proven live with an actual
Viewer-vs-Admin contrast, not merely "login is now required." A real,
already-existing audit trail was confirmed rather than built redundantly. A
deliberately narrower scope than full OIDC was chosen WITH the user's
explicit sign-off, given the real absence of any shared identity provider —
not assumed unilaterally on a decision that size. Two real bugs were found
via live verification and fixed with the root cause understood, not papered
over. This opens `phase-07-security-privacy-config-governance`.
