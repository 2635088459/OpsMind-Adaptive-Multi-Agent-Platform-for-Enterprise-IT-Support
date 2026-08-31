# ADR-0003: Configuration is GitOps — version-pinned, reviewed, base + environment overlays

> Status: Accepted
> Date: 2026-08-30
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context

Observability configuration (pipelines, rules, dashboards, retention, routing) is
operationally sensitive: a bad change can drop all telemetry, silence a critical
alert, or blow up cardinality. It must be reviewable, reproducible across
local/CI/production, and reversible.

## Decision

All observability configuration lives in `infrastructure/observability/` in Git and is
delivered GitOps-style:

1. **Version-pinned.** Component images are pinned by tag **and** digest in
   `versions.env`. No `:latest`, no floating tags.
2. **Reviewed.** Changes land via pull request with a `platform-observability`
   `CODEOWNERS` review. High-risk changes (retention cut, deletion, critical-alert
   silence) additionally require domain-06 approval and an audit record
   (`SPEC-OP-032`).
3. **Base + overlays.** Each component has `base/` (environment-independent) and
   `overlays/{local,ci,production}/` (environment parameters only). Overlays never
   change logic, rules, dashboards, or weaken a control defined in `base/`
   (see `docs/environment-overlays.md`).
4. **Validated in CI.** `observability-ci.yml` runs the layout validator and, as
   component configs land, the native validators (`otelcol validate`,
   `promtool check`, `amtool check-config`, Loki/Tempo config checks).
5. **Reversible.** Every governed artifact declares a `rollback:` instruction; revert
   = `git revert` + redeploy.

## Consequences

- Any environment can be reproduced from a commit SHA.
- Drift between environments is limited to declared overlay parameters.
- Emergency changes still go through Git; break-glass is a fast-tracked PR, not a
  console edit. The thin control-plane API (ADR-0005) exists precisely for the few
  changes that cannot wait or cannot be expressed as files.
- Contributors must learn the base/overlay split; the layout doc and validator make it
  mechanical.

## Alternatives considered

- **Console-managed Grafana / Alertmanager.** Rejected as the source of truth:
  unreviewable, non-reproducible. Console is read/explore only; provisioning comes
  from Git.
- **Single monolithic per-environment config.** Rejected: duplicates logic, invites
  drift, hides what actually differs between environments.
- **Templating everything with one tool now (Helm/Kustomize/Jsonnet).** Deferred: the
  mechanism is chosen per component in `SPEC-OP-002`/`SPEC-OP-008`; the contract only
  requires that the merged result passes native validation.
