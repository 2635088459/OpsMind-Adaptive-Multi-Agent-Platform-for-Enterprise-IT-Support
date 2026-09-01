# ConfigurationChangeRollback

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-032
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: n/a (this runbook IS the rollback procedure for a bad config change)
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-032-traceability.md

Not linked from any alert — an operational reference doc, same category as
`AlertRoutingAndSilencing.md` and `ObservabilityAccessControl.md` (a known,
accepted `validate-rule-catalog.py` "orphaned catalog entry" warning, not a
gap). Describes this domain's real, proven config-change rollback path.

## When to use this

A merged config change to `infrastructure/observability/**` behaves
unexpectedly once deployed — a component fails to start, a signal stops
being ingested, a dashboard/rule breaks, or a real regression is found in
production. This domain has no database-backed "configuration change"
record to roll back (`ADR-0009`) — the rollback path is Git itself.

## Procedure (proven live under SPEC-OP-032)

1. **Identify the bad commit.** Every governed config file names its own
   `# spec:` in a header comment — `git log --follow -- <file>` shows every
   change to it, attributed and reviewed (CODEOWNERS). Confirm the exact
   commit SHA that introduced the regression.
2. **Revert it:**
   ```sh
   git revert <sha>
   ```
   A merge commit needs `git revert -m 1 <sha>`. This creates a NEW commit
   undoing exactly that change — history is never rewritten, so the bad
   version, the revert, and every review/CI record for both remain visible.
3. **Redeploy the affected component(s).** Every base/overlay config file's
   own `# rollback:` header names the exact command — the general local-dev
   form:
   ```sh
   docker compose \
     --env-file infrastructure/observability/versions.env \
     --env-file infrastructure/observability/prometheus/overlays/local/values.env \
     --env-file infrastructure/observability/alertmanager/overlays/local/values.env \
     --env-file infrastructure/observability/collector/overlays/local/values.env \
     -f infrastructure/docker-compose/observability-stack.yml \
     up -d --force-recreate <service>
   ```
   (Omitting `<service>` recreates every container that changed.)
4. **Re-verify.** Re-run `scripts/observability-stack.sh smoke` and the full
   validator sweep (`validate-observability-layout.py`,
   `validate-telemetry-governance.py`, `validate-signal-contracts.py`,
   `validate-collector-pipeline.py`, `validate-dashboards.py`,
   `validate-rule-catalog.py`, `validate-config-change-audit.py`) — the same
   bar a normal spec closure meets, not a lighter one because it's "just" a
   rollback.
5. **Push the revert through the same PR + CODEOWNERS review + CI gate as
   any other change.** A rollback is not an exception to Git review — it is
   itself a governed config change.

## Real proof this works (SPEC-OP-032)

Executed live, not merely documented: bumped
`tempo/overlays/local/values.env`'s `TEMPO_BLOCK_RETENTION` from `24h` to
`48h` (commit `f4a7a8c`), redeployed Tempo, confirmed
`block_retention: 48h0m0s` via Tempo's own `/status/config`; `git revert
f4a7a8c` (commit `c15b988`), redeployed again, confirmed
`block_retention: 24h0m0s` — the exact original value, live, with Tempo
healthy throughout both cycles. Full command-by-command evidence:
`docs/traceability/domains/08-observability-platform/SPEC-OP-032-traceability.md`.

## Audit trail

`git log`/`git blame` on the affected file(s) IS the audit record: who
changed what, under which `SPEC-OP-0xx`, reviewed by whom (CODEOWNERS),
passing which CI run (`observability-platform-ci.yml`'s `layout`/`config`/
`smoke` jobs) — nothing further needs to be reconstructed after the fact.
`scripts/validate-config-change-audit.py` (SPEC-OP-032) now enforces, in CI,
that every component config file keeps the `# owner: / # spec: / # rollback:`
header this depends on — a future file missing it fails CI instead of
silently breaking this trail.
