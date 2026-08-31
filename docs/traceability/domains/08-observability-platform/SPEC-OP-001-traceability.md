# SPEC-OP-001 Traceability — Platform Boundaries And Repository Layout

> Domain: `08-observability-platform`
> Phase: `phase-00-platform-engineering-foundation`
> Status: implemented
> Verified: 2026-08-30
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Define data/control-plane ownership, forbidden business writes,
component responsibility matrix, and ADRs* — plus version-pinned configuration with
environment overlays, artifact metadata conventions, and traceability evidence.

| Spec deliverable / acceptance clause | Where it is satisfied |
|---|---|
| Data-plane / control-plane ownership | `infrastructure/observability/docs/platform-boundaries.md` §1–§3 |
| Forbidden business writes | `infrastructure/observability/docs/forbidden-business-writes.md` (F1–F8 + enforcement) |
| Component responsibility matrix | `infrastructure/observability/docs/component-responsibility-matrix.md` (6 components + cross-cutting) |
| ADRs | `infrastructure/observability/docs/adr/0001`–`0006` + `docs/adr/README.md` |
| Version-pinned configuration | `infrastructure/observability/versions.env` (IMAGE+TAG+DIGEST per component), `VERSIONS.md` (upgrade policy) |
| Environment overlays | `infrastructure/observability/docs/environment-overlays.md` + `*/base/` + `*/overlays/{local,ci,production}/` skeleton |
| Signal / query / dashboard / rule / runbook artifact conventions | `infrastructure/observability/docs/artifact-metadata-convention.md` + `schemas/artifact-metadata.schema.json` + per-directory `README.md` |
| "Configuration validates … reproducibly in local/CI" | `scripts/validate-observability-layout.py` + `.github/workflows/observability-platform-ci.yml` (layout/version/boundary gate; native config gate scaffolded for SPEC-OP-002) |
| Secret/PII scan + cardinality budget | validator scans committed Collector/Alertmanager/Grafana config for `:latest` and business-write targets; boundary docs fix the cardinality rule; live scans are SPEC-OP-007 / SPEC-OP-031 |
| "dashboard/rule/runbook has owner and version" | metadata convention + JSON Schema + validator `_check_meta` (fails on missing field / non-SemVer version) |
| Ownership / review | `infrastructure/observability/CODEOWNERS`, ADR-0006 |
| Traceability records files, commands, results, residual risks | this document + `…/SPEC-OP-001-…/traceability-entry.yaml` |

Out of scope for SPEC-OP-001 (deferred, with owning spec): running component config
(`SPEC-OP-002`, `SPEC-OP-008`+), live ingestion/query/correlation of a real producer
signal (`SPEC-OP-002`), retention/capacity/backup sizing (`SPEC-OP-015`), dependency
outage / overload / rollback drills against a live stack (`SPEC-OP-034`, `SPEC-OP-035`).

## 2. LLD slice coverage

Per `phase-spec-coverage-matrix`: `02-business-invariants`, `12-observability`,
`13-package-and-class-design`.

| Slice | How SPEC-OP-001 addresses it |
|---|---|
| 02-business-invariants | Boundary rules stated as invariants: signals immutable & source-owned; config versioned & reviewed; business availability > telemetry delivery; every artifact declares owner/version/access/retention/runbook/rollback/audit; no secrets/PII/raw prompts. Encoded in `platform-boundaries.md`, `forbidden-business-writes.md`, and enforced by the validator. |
| 12-observability | Establishes the observability-of-observability groundwork: component failure behaviors and self-alert expectations in the responsibility matrix; `signals/` and `runbooks/` homes; metadata `audit_ref`. Full self-monitoring is `SPEC-OP-033`. |
| 13-package-and-class-design | The configuration directory + component design: `repository-layout.md`, the `base/ + overlays/` shape, naming rules, `versions.env`, `CODEOWNERS`, and the schema. |

## 3. Files added / changed

```text
infrastructure/observability/
  README.md
  versions.env
  VERSIONS.md
  CODEOWNERS
  docs/platform-boundaries.md
  docs/forbidden-business-writes.md
  docs/component-responsibility-matrix.md
  docs/repository-layout.md
  docs/environment-overlays.md
  docs/artifact-metadata-convention.md
  docs/adr/README.md
  docs/adr/0001-otel-collector-sole-ingestion-boundary.md
  docs/adr/0002-prometheus-loki-tempo-backends.md
  docs/adr/0003-gitops-versioned-config-with-overlays.md
  docs/adr/0004-observability-never-mutates-business-state.md
  docs/adr/0005-thin-control-plane-api-only-when-gitops-insufficient.md
  docs/adr/0006-repository-layout-and-ownership-model.md
  schemas/artifact-metadata.schema.json
  {collector,prometheus,loki,tempo,grafana,alertmanager}/README.md
  {collector,prometheus,loki,tempo,grafana,alertmanager}/base/.gitkeep
  {collector,prometheus,loki,tempo,grafana,alertmanager}/overlays/{local,ci,production}/.gitkeep
  rules/README.md  rules/recording/.gitkeep  rules/alerting/.gitkeep
  dashboards/README.md  dashboards/.gitkeep
  runbooks/README.md  runbooks/TEMPLATE.md
  signals/README.md  signals/fixtures/.gitkeep

scripts/validate-observability-layout.py
scripts/tests/test_validate_observability_layout.py
.github/workflows/observability-platform-ci.yml

docs/specs/domains/08-observability-platform/SPEC-OP-001-.../traceability-entry.yaml   (updated)
docs/traceability/domains/08-observability-platform/SPEC-OP-001-traceability.md        (this file)
```

## 4. Commands run and results

| Command | Result |
|---|---|
| `python scripts/validate-observability-layout.py` | `0 error(s), 6 warning(s)` — the 6 warnings are the `*_DIGEST=PENDING-SPEC-OP-002` placeholders (expected until SPEC-OP-002). Exit 0. |
| `python -m unittest discover -s scripts/tests -v` | `Ran 8 tests … OK` — regex (floating-tag / SemVer), metadata parsing (md + yaml), and 3 end-to-end cases (real tree passes; missing ADR fails; floating tag fails). |

Interpreter on the verification machine: uv-managed CPython 3.14.7
(`~/AppData/Roaming/uv/python/...`). CI targets CPython 3.12 (`setup-python@v5`). The
validator and tests are standard-library only (`json`, `re`, `pathlib`, `unittest`,
`subprocess`, `shutil`, `tempfile`).

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Component image digests are `PENDING-SPEC-OP-002`; tag-only pinning is mutable in principle | Low | `SPEC-OP-002` pins digests on first pull; validator downgrades the check to a warning until then, error afterwards |
| Native config validators (`otelcol validate`, `promtool`, `amtool`, `loki`/`tempo -config.check`) are scaffolded as comments, not yet running | Low | No config files exist to validate yet; `SPEC-OP-002` uncomments them as it adds `base/`/`overlays/` content |
| Pinned tags (`otel 0.116.0`, `prometheus v3.1.0`, `loki 3.3.2`, `tempo 2.7.1`, `grafana 11.4.0`, `alertmanager v0.28.0`) were chosen from the versioning policy, not yet pulled/tested | Low | `SPEC-OP-002` local topology bring-up is the first real test; upgrade policy in `VERSIONS.md` covers bumps |
| `CODEOWNERS` references `@opsmind/platform-observability`; the GitHub team may not exist yet | Low | Create the team, or adjust to individual handles, before enabling branch protection on this path |
| Repo has no root `CODEOWNERS`; the scoped file only applies if GitHub is configured to read nested CODEOWNERS (it is, by default) | Info | Documented in the file header |
| Forbidden-write static scan is heuristic (regex on known business paths / exporter names) | Medium | Primary control is `CODEOWNERS` review against `forbidden-business-writes.md`; scan is a backstop, tightened as real Collector config lands |

## 6. Sign-off

Foundation for `phase-00` is in place: boundaries, forbidden writes, responsibility
matrix, ADRs, version pinning, overlay strategy, metadata convention, and an enforcing
CI gate. `SPEC-OP-002` (Local Observability Topology) can now populate
`*/base/` + `*/overlays/local/` against this contract.
