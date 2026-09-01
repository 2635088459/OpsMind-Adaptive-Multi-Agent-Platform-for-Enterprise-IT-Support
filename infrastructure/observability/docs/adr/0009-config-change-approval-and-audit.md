# ADR-0009: Configuration change governance is Git review + CI + git-log audit + a proven git-revert rollback — no new control plane

> Status: Accepted
> Date: 2026-09-01
> Spec: SPEC-OP-032
> Deciders: platform-observability

## Context

`SPEC-OP-032`'s objective is "govern config lifecycle through Git review, CI,
approval, audit, deployment, and rollback." This domain has been GitOps-only
since `ADR-0003` (versioned config, environment overlays, no thin control
plane beyond what `ADR-0005` already scoped) and has never had, and does not
plan to build, a database-backed "configuration change" record — every
config artifact already lives as a reviewed, version-controlled file.

Investigating what was real vs. missing found most of this objective already
built, just never assembled into one coherent, spec-owned story:

- **Git review**: `infrastructure/observability/CODEOWNERS` has required
  `@opsmind/platform-observability` review on this whole tree since
  `SPEC-OP-001`/`ADR-0006`.
- **CI**: `.github/workflows/observability-platform-ci.yml`'s `layout` and
  `config` jobs already run every validator in this domain on every PR
  touching this tree.
- **Audit reference**: every governed artifact (dashboards, rules, runbooks,
  signal docs) already has its `audit_ref`/metadata header checked by
  `validate-observability-layout.py`'s `check_governed_artifacts()` — but the
  actual deployable component config files themselves
  (`collector/base/config.yaml`, `prometheus/base/prometheus.yml`,
  `loki/base/loki.yml`, `tempo/base/tempo.yml`,
  `alertmanager/base/alertmanager.yml`, and every file under their
  `overlays/{local,ci,production}/`) were never covered by that check —
  despite every one of them, empirically, already carrying the same
  `# owner: / # spec: / # rollback:` header-comment convention since
  `SPEC-OP-002`. That convention being followed everywhere already but
  enforced nowhere is this spec's one genuine gap.
- **Rollback**: every one of those same header comments already states
  `rollback: git revert <sha>; ... --force-recreate <service>` — but no spec
  had ever actually *exercised* it live before this one (§3).
- **"Approval"**: unlike domains 01–07, this domain has no hexagonal service
  and no runtime write API of its own — Grafana/Prometheus/Alertmanager each
  have their own authentication (`SPEC-OP-030`), not a domain-01-issued
  token, and nothing here calls domain-06's approval workflow. The generic
  per-spec template phrase "administrative writes require domain-01
  identity ... and domain-06 approval when risk is high" (repeated near-
  verbatim across many `SPEC-OP-0xx` API-contract docs) does not describe a
  real surface in this domain — there is no runtime "change request" a human
  approves through an API. The real approval mechanism here **is** the
  required PR review CODEOWNERS already enforces.

## Decision

- **No new control-plane or database is built.** Git IS the configuration-
  change record: a merged PR to this tree already required
  CODEOWNERS-team review + a green CI run (`layout`, `config`, `smoke`) —
  that pairing (review + automated gate) **is** this domain's approval
  mechanism, not a placeholder for a future one.
- **Audit reference is now CI-enforced, not just habitual.** New
  `scripts/validate-config-change-audit.py` (wired into the `layout` CI job)
  requires every non-empty component config file (base + all 3 overlays,
  across all 6 components) to carry `# owner:`, `# spec:` (naming a real
  `SPEC-OP-0xx` id), and `# rollback:` (naming a real, executable mechanism —
  `git revert` or `recreate <service>`) in its first 10 lines. `git
  log`/`git blame` on a header-commented file is the actual audit trail:
  who changed what, under which spec, when, reviewed by whom (CODEOWNERS),
  passing which CI run.
- **Rollback is a proven mechanism, not an assertion.** This spec's own
  traceability doc records a real, live `git revert` + redeploy +
  re-verification cycle against Tempo's `block_retention` (§3 there) — the
  first time any spec in this domain has actually exercised the rollback
  path its own header comments have claimed since `SPEC-OP-002`.
- **GitHub branch protection is documented, not configured by this
  session.** The exact required settings (below) are the intended real
  enforcement of "Git review" + "CI" as hard gates, not merely conventions a
  contributor could bypass by force-pushing directly to `main`. Configuring
  them requires GitHub repository-admin access this environment does not
  have (no `gh` CLI / admin token available here) — stated as a real,
  honest operational gap, not silently assumed done:
  - Require a pull request before merging, with at least 1 approving review
    from a CODEOWNERS-matched reviewer.
  - Require these status checks to pass before merging:
    `Observability Platform CI / Layout and Boundary Validation`,
    `Observability Platform CI / Component Config Validation`,
    `Observability Platform CI / Smoke Test` (the 3 jobs in
    `observability-platform-ci.yml`).
  - Require branches to be up to date before merging.
  - Do not allow force-pushes or deletions of `main`.

## Consequences

- A future spec that adds a new component config file without the header
  trio now fails CI (`validate-config-change-audit.py`) instead of silently
  slipping through — the convention this domain has followed by habit since
  `SPEC-OP-002` can no longer quietly lapse.
- "Approval" for this domain is honestly narrower than the generic template
  phrase implies: PR review, not a runtime workflow. This is stated
  explicitly rather than inventing a fake domain-06 integration call this
  domain has no real surface to make.
- The GitHub branch-protection settings above remain a documented,
  un-verified-live requirement until a repository admin configures them —
  a real residual item, tracked in this spec's own traceability doc, not
  hidden.

## Alternatives considered

- **Build a dedicated "ConfigurationRelease" database table + admin API**,
  mirroring domain 06's `ApprovalRequest` model. Rejected: this domain has
  deliberately never had a hexagonal service or a database of its own
  (`ADR-0005`'s "thin control plane only when GitOps is insufficient" already
  settles this) — Git already IS a change-request record with review,
  timestamps, and attribution; duplicating that in a new service would be
  scope creep with no real gap it closes.
- **Call domain-06's real approval API for "high-risk" config changes.**
  Rejected for now: nothing in this domain's actual deployment path ever
  submits a runtime change request domain-06 could approve — every change
  here is a file in Git. Revisit only if a future spec introduces a genuine
  runtime admin-write surface in this domain that needs it.
