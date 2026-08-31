# `dashboards/` — Grafana dashboard source of truth

> Owner: `platform-observability`
> Filled by: `SPEC-OP-016` (golden path), `SPEC-OP-017` (domain), `SPEC-OP-018` (infra), `SPEC-OP-019` (agent/LLM cost/capacity)
> Provisioned by: `../grafana/base/provisioning/dashboards/`

## Rules for files added here

- File name: `<area>-<name>.json` (e.g. `golden-path-service-overview.json`).
- Each JSON carries `__opsmind_meta` with the full metadata block and mirrors
  `owner` / `version` into `tags`
  ([artifact-metadata-convention §3](../docs/artifact-metadata-convention.md)).
- Dashboards are **view only**: panel links and data-source queries never mutate a
  business resource ([forbidden-business-writes](../docs/forbidden-business-writes.md) F3).
- A computed SLO % / error budget / cost figure shown here is a view, never written
  back as a domain fact ([ADR-0004](../docs/adr/0004-observability-never-mutates-business-state.md)).
- Edits are made in Git, not the Grafana console
  ([ADR-0003](../docs/adr/0003-gitops-versioned-config-with-overlays.md)).
- CI validates JSON parse + presence of `__opsmind_meta`; a `jsonnet`/`grafana`
  lint may be added by `SPEC-OP-016`.
