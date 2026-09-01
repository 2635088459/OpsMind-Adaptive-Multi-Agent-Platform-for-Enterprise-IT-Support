# SPEC-OP-032 Traceability — Configuration Change Approval And Audit

> Domain: `08-observability-platform`
> Phase: `phase-07-security-privacy-config-governance` (closes this phase)
> Status: implemented
> Verified: 2026-09-01
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Govern config lifecycle through Git review, CI,
approval, audit, deployment, and rollback."*

| Requirement | Where | Scope |
|---|---|---|
| Git review | `CODEOWNERS` (real since SPEC-OP-001) | Already real; confirmed, not built |
| CI | `.github/workflows/observability-platform-ci.yml` (real since early phases) | Already real; 1 env-file gap fixed, 1 new validator added |
| Audit | New `scripts/validate-config-change-audit.py`, CI-enforced | The one genuine gap: the `# owner:/# spec:/# rollback:` header convention was universal but never machine-checked |
| Approval | CODEOWNERS PR review IS the real mechanism | Domain 08 has no runtime write API — the generic "domain-06 approval" template phrase does not describe a real surface here |
| Rollback | `git revert` + redeploy, proven live | First time any spec in this domain has actually exercised it, not just asserted it in a header comment |

## 2. What was found already real vs. genuinely missing

This domain has been GitOps-only since `ADR-0003`. Investigating this
spec's starting point found:

- **Git review**: `infrastructure/observability/CODEOWNERS` has required
  `@opsmind/platform-observability` review on this whole tree since
  `SPEC-OP-001`/`ADR-0006` — real, unchanged.
- **CI**: `observability-platform-ci.yml`'s `layout`, `config`, and `smoke`
  jobs already run every validator + the full smoke suite on every PR — real,
  unchanged except for one adjacent fix (§4.2).
- **Audit reference, partially real**: `validate-observability-layout.py`'s
  `check_governed_artifacts()` already enforces a metadata header (including
  `audit_ref`) on dashboards/rules/runbooks/signal docs — but the actual
  deployable component config files (`collector/base/config.yaml`,
  `prometheus/base/prometheus.yml`, `loki/base/loki.yml`,
  `tempo/base/tempo.yml`, `alertmanager/base/alertmanager.yml`, and every
  file under their `overlays/{local,ci,production}/`) were never in that
  scan — despite every one of them already carrying the same
  `# owner: / # spec: / # rollback:` header comment since `SPEC-OP-002`.
  Checked empirically: **every non-empty file in scope already had it** — a
  universal-by-habit convention, enforced nowhere. This is the one real gap
  this spec closes.
- **Rollback, asserted but never exercised**: every one of those same header
  comments has stated a `rollback: git revert <sha>; ...` command since
  `SPEC-OP-002` — but no prior spec had ever actually run it end-to-end
  against a live stack. Doing so for real is this spec's other concrete,
  new deliverable (§4.1).
- **Approval, honestly narrower than the generic template**: domain 08 has
  no hexagonal service, no database, and no runtime write API — unlike
  domains 01–07. Grafana/Prometheus/Alertmanager each authenticate with
  their own mechanism (`SPEC-OP-030`), not a domain-01 token, and nothing in
  this domain calls domain-06's real `ApprovalRequest` API. The generic
  per-spec API-contract phrase ("administrative writes require domain-01
  identity ... and domain-06 approval when risk is high") appears
  near-verbatim across many `SPEC-OP-0xx` docs and does not describe a real
  surface in this one. Rather than fabricate a fake domain-06 integration
  call, this is stated plainly: **the real approval mechanism here is the
  required CODEOWNERS PR review itself** — full reasoning in `ADR-0009`.

## 3. What was built

- **`ADR-0009`** — the coherent governance story: Git review + CI + git-log
  audit + a proven rollback, deliberately no new control plane or database
  (mirrors `ADR-0005`'s own "thin control plane only when GitOps is
  insufficient"). Documents the exact GitHub branch-protection settings that
  *should* be configured (require PR + CODEOWNERS review, require the 3 CI
  jobs to pass, require up-to-date branches, no force-push/delete on `main`)
  — explicitly marked as **not verified live**, since this environment has
  no `gh` CLI or GitHub admin token to configure or confirm them (a real,
  stated operational gap, not silently assumed done).
- **`scripts/validate-config-change-audit.py`** (new, wired into CI's
  `layout` job) — for every non-empty `*.yml`/`*.yaml`/`*.env` file under
  each of the 6 components' `base/` or `overlays/{local,ci,production}/`:
  requires `# owner:`, `# spec:` (naming a real `SPEC-OP-0xx` id, not a
  content-free placeholder), and `# rollback:` (naming `git revert` or
  `recreate <service>` — this repo's two real, established idioms) within
  the file's first 10 lines. Ran against the real tree: **17 config files
  scanned, 0 errors** — the convention really was already universal.
- **`infrastructure/observability/runbooks/ConfigurationChangeRollback.md`**
  — the real, proven rollback procedure (identify the bad commit via
  `git log --follow`, `git revert`, redeploy via the file's own `rollback:`
  header command, re-verify via the full validator + smoke sweep, push the
  revert through the same review/CI gate as any change).
- **Adjacent fixes** found while assembling this: the CI `config` job's
  compose render was missing `--env-file collector/overlays/local/values.env`
  (harmless for a static `docker compose config` render, but inconsistent
  with the `smoke` job's own correct env-file list — fixed for consistency);
  `ADR-0007`/`ADR-0008` (both real, both created in earlier specs) had never
  been added to `docs/adr/README.md`'s index nor
  `validate-observability-layout.py`'s required-ADR list — added both,
  closing a small tracking gap.

## 4. Real evidence gathered live

### 4.1 A genuine, end-to-end `git revert` rollback proof

Not merely asserted — executed against a running stack:

```text
1. curl .../status/config (baseline)         -> block_retention: 24h0m0s
2. edit tempo/overlays/local/values.env:       TEMPO_BLOCK_RETENTION 24h -> 48h
3. git commit                                  f4a7a8c
4. docker compose up -d --force-recreate tempo
5. curl .../status/config                    -> block_retention: 48h0m0s  (confirmed changed)
6. git revert --no-edit f4a7a8c                c15b988
7. docker compose up -d --force-recreate tempo
8. curl .../status/config                    -> block_retention: 24h0m0s  (confirmed restored, exact original value)
```

Tempo reported `healthy` throughout both recreate cycles. This is the first
time any spec in this domain's history has actually exercised the rollback
mechanism its own header comments have claimed since `SPEC-OP-002`.

### 4.2 Full validator + test + smoke sweep

- `validate-observability-layout.py` 0 err/0 warn (a transient warning about
  this doc's own `audit_ref` not existing yet resolved once this file was
  written).
- `validate-telemetry-governance.py`, `validate-signal-contracts.py`,
  `validate-collector-pipeline.py` — all 0 err/0 warn, unaffected.
- `validate-dashboards.py` 0 err/1 pre-existing warn (unrelated,
  datasource-uid lookup, unchanged from before this spec).
- `validate-rule-catalog.py` 0 err/12 warn (11 pre-existing + 1 new expected
  "orphaned catalog entry" for `ConfigurationChangeRollback.md`, same
  accepted category as `AlertRoutingAndSilencing.md`/
  `ObservabilityAccessControl.md`).
- `validate-config-change-audit.py` (new) 0 err/0 warn — 17 real config
  files scanned.
- `scripts/tests/` (pytest/unittest): **88 passed** (6 new, all for the new
  validator: real-tree-passes, missing-owner-header-fails,
  spec-header-without-a-real-id-fails, rollback-header-without-a-real-
  mechanism-fails, empty-placeholder-overlay-is-ignored,
  new-config-file-without-any-header-fails).
- `scripts/observability-stack.sh smoke` — **SMOKE: PASS**, every
  `SPEC-OP-002`~`031` assertion green, unaffected by this spec's changes.
- Stack torn down clean.

## 5. Residual risks / honest limitations

| Risk | Severity | Mitigation / owner |
|---|---|---|
| GitHub branch-protection settings (`ADR-0009`) are documented but not configured or verified live | Medium — a real, stated gap | needs a repository admin with GitHub access (no `gh` CLI/admin token available in this environment); a genuine follow-up whenever that access exists |
| "Approval" for this domain is PR review only, not a runtime workflow | Low — an honest scope statement, not a hidden gap | if a future spec ever introduces a real runtime admin-write surface in this domain, a real domain-06 integration would then make sense; none exists today |
| The audit-header check validates presence/shape, not semantic correctness of the `# rollback:` command itself | Low | a header could name a syntactically-valid but wrong command; the live rollback proof (§4.1) is the real-world check that at least one real instance of this mechanism genuinely works |

## 6. Sign-off

Configuration-change governance for this domain is now a coherent,
spec-owned story rather than several unconnected habits: Git review and CI
were already real and are unchanged; the audit-reference convention that was
universal-by-habit since `SPEC-OP-002` is now CI-enforced against silent
regression; and the rollback mechanism every header comment has claimed
since day one was, for the first time, actually exercised end-to-end against
a live stack and proven to work exactly as documented. "Approval" was scoped
honestly to what this domain's real surfaces support — PR review — rather
than fabricating a domain-06 integration with no real call site. This closes
`phase-07-security-privacy-config-governance` in full.
