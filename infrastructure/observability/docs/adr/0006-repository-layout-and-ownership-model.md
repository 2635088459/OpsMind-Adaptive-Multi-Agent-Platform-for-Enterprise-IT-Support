# ADR-0006: Repository layout and ownership model for `infrastructure/observability/`

> Status: Accepted
> Date: 2026-08-30
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context

Thirty-six Observability Platform specs will each add configuration, rules,
dashboards, runbooks, or contracts. Without a fixed layout and a single owner, files
land inconsistently, reviews miss the boundary rules, and later specs cannot rely on
where things are.

## Decision

### Layout

All observability assets live under `infrastructure/observability/`, in the tree fixed
by `docs/repository-layout.md`:

- `docs/` + `docs/adr/` — governance (this spec).
- `signals/` — producer contracts (`SPEC-OP-004`–`007`).
- `collector/`, `prometheus/`, `loki/`, `tempo/`, `grafana/`, `alertmanager/` — each
  with `base/` + `overlays/{local,ci,production}/` and a `README.md`.
- `rules/{recording,alerting}/`, `dashboards/`, `runbooks/` — promoted catalogs.
- `schemas/` — validation schemas.

Naming, per-file metadata headers, and the "must never appear" list are part of the
contract and enforced by `scripts/validate-observability-layout.py`.

### Ownership

- Single accountable team: `platform-observability`, declared in
  `infrastructure/observability/CODEOWNERS`.
- Every governed artifact names an `owner` in its metadata header (usually
  `platform-observability`; a domain team may co-own a domain dashboard).
- Source-domain teams own the **semantics** of their signals; `platform-observability`
  owns transport, storage, and the catalogs.

### Change flow

PR → `CODEOWNERS` review against the boundary rules → CI (`observability-ci.yml`
layout validator + component-native validators) → merge → deploy identically to all
environments via overlays.

## Consequences

- Later specs have a known home and a known checklist; reviews are mechanical.
- The validator is now a required status check for `infrastructure/observability/**`.
- Adding a new component means a new top-level dir following the same
  `base/ + overlays/ + README.md` shape, plus a `versions.env` entry and, if it
  changes an architectural choice, a new ADR.

## Alternatives considered

- **Per-service observability config colocated with each service.** Rejected: splits
  the boundary enforcement and the catalogs; no single review surface.
- **A separate repository for observability.** Rejected for this monorepo project:
  cross-domain contract specs (`SPEC-OP-025`–`029`) need to reference service code and
  specs in the same tree.
