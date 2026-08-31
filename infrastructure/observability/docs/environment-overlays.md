# Environment Overlay Strategy

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Status: authoritative

Observability configuration is authored once in `base/` and specialized per
environment in `overlays/`. This keeps `local`, `ci`, and `production` behavior
identical in shape and different only in scale, endpoints, retention, and auth.

## 1. Environments

| Overlay | Target | Storage | Auth | Retention |
|---|---|---|---|---|
| `local` | `infrastructure/docker-compose` dev stack (`SPEC-OP-002`) | named docker volumes | none / static admin | short (hours–days) |
| `ci` | ephemeral topology in CI (compose or Testcontainers) | `tmpfs` / throwaway | none | test-run lifetime |
| `production` | documented production topology (`SPEC-OP-008`+) | PVC / object storage | OIDC (domain-01), scoped service tokens | per `SPEC-OP-015` |

## 2. What an overlay MAY set

- Backend endpoints and ports.
- Replica counts and resource requests / limits.
- Retention windows, block / chunk sizes, compaction cadence.
- Storage class / PVC size / object-storage bucket.
- Auth mode and issuer URL.
- Sampling **rates** within the floor defined in `base/` (never below it).
- Scrape / discovery targets specific to the environment.

## 3. What an overlay MUST NOT do

- Redefine Collector pipeline structure, processor order, or which redaction
  processors run.
- Change rule expressions, alert thresholds semantics, or dashboard panels
  (those are promoted through `rules/` and `dashboards/`, not overlays).
- Lower a sampling floor, remove a redaction step, or raise a cardinality limit set
  in `base/`.
- Introduce an exporter / receiver / receiver targeting a business system.
- Embed a secret. Secrets are injected at deploy time from the environment's secret
  store and referenced by name only.

## 4. Composition mechanism

- **Collector**: `base/config.yaml` plus overlay fragments merged with the Collector's
  native multi-`--config` support (later files override scalars, lists are replaced
  key-path by key-path). Documented per overlay in its `README.md`.
- **Prometheus / Loki / Tempo / Alertmanager**: `base/<component>.yml` plus an overlay
  `<component>.env` / small YAML patch applied by the deployment tool (compose
  `env_file` / Kustomize / Helm values — chosen in `SPEC-OP-002` for local and
  `SPEC-OP-008`+ for production).
- **Grafana**: `base/provisioning/**` is environment-independent; overlay supplies
  `grafana.ini` fragments and datasource URLs.

Whatever the mechanism, the merged result for every environment must pass the same
component-native validation (`otelcol validate`, `promtool check`, `amtool check-config`,
`loki -verify-config`, `tempo -config.check`) — wired in `SPEC-OP-002` / component
specs and gated in `observability-ci.yml`.

## 5. Promotion flow for rules and dashboards

```text
author in a component/experiment branch
  → validate with component tooling
  → peer review (CODEOWNERS)
  → merge to rules/ or dashboards/ with full metadata header
  → referenced by base/ (never by an overlay)
  → deployed to every environment identically
```

Environment-specific muting is done with Alertmanager routes / silences, not by
forking a rule into an overlay.
