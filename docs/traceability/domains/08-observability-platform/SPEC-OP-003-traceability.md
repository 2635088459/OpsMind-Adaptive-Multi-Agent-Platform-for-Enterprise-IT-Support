# SPEC-OP-003 Traceability — Telemetry Governance Baseline

> Domain: `08-observability-platform`
> Phase: `phase-00-platform-engineering-foundation`
> Status: implemented
> Verified: 2026-08-30 (validators pass; smoke test proves deny-list enforcement; stack torn down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Define signal allow/deny fields, owners, retention classes,
cardinality budgets, schema review, and exception workflow.*

| Spec deliverable | Where |
|---|---|
| Signal allow / deny fields | `governance/telemetry-governance.yaml` → `deny_fields` (auth/credential/token/MFA/OTP/raw-prompt/raw-user-text/PII-core, each with a reason) and `allow_fields` (per resource/span/log/metric_datapoint: `required` + `recommended`) |
| Owners | `governance/telemetry-governance.yaml` → `signal_owners` (family glob → semantic owner + transport owner) |
| Retention classes | `governance/telemetry-governance.yaml` → `retention_classes` (`debug`/`standard`/`slo`/`audit`, each with `local`/`ci`/`prod` durations); artifacts now reference the class name in their `retention:` metadata |
| Cardinality budgets | `governance/telemetry-governance.yaml` → `cardinality_budgets` (`global.max_series_total` + per-namespace `max_label_keys` / `max_series` / `forbidden_labels`) |
| Schema review | `governance/telemetry-governance.yaml` → `schema_review` (`additive-or-versioned`) + `docs/telemetry-governance.md` §5 |
| Exception workflow | `docs/telemetry-governance.md` §6 (request → `exceptions:` entry → PR review → domain-06 for deny-field waivers → renew/close; ≤ 90-day window) + `exceptions: []` in the manifest |
| Human policy doc | `docs/telemetry-governance.md` |
| Schema | `schemas/telemetry-governance.schema.json` |
| Enforcement in the pipeline | `collector/base/config.yaml` → `processors.transform/governance` deletes deny-listed keys from resource/span/datapoint/log attributes in all three pipelines; regex is the canonical alternation of `deny_fields[].pattern` |
| CI gate | `.github/workflows/observability-platform-ci.yml` `layout` job runs `scripts/validate-telemetry-governance.py` + self-tests; `smoke` job asserts deny keys are stripped end-to-end |
| "Secret/PII scan and cardinality budget pass" | validator baseline-concept check + Collector-sync check + `forbidden_labels` declared per namespace; smoke proves runtime stripping |
| Traceability | this file + `traceability-entry.yaml` |

Deferred (owning spec): producer-side `required` attribute conformance tests with
Java/Python fixtures (`SPEC-OP-004`); W3C propagation (`SPEC-OP-005`); metric-naming +
`promtool`/`prometheus_tsdb_head_series` cardinality tooling (`SPEC-OP-006`); free-text
log **value** redaction (`SPEC-OP-007`); capacity-modelled production retention numbers
(`SPEC-OP-015`).

## 2. Files added / changed

```text
infrastructure/observability/
  governance/telemetry-governance.yaml              NEW
  schemas/telemetry-governance.schema.json          NEW
  docs/telemetry-governance.md                      NEW
  collector/base/config.yaml                        CHANGED  (transform/governance processor + pipeline wiring)
  rules/recording/platform-self.yml                 CHANGED  (retention: standard; v0.1.1)
  rules/alerting/platform-self.yml                  CHANGED  (retention: audit; v0.1.1)
  runbooks/TargetDown.md                            CHANGED  (retention: audit; v0.1.1)
  docs/repository-layout.md                         CHANGED  (governance/ row)
  docs/artifact-metadata-convention.md              CHANGED  (retention = class name)
  signals/README.md                                 CHANGED  (points at the governance manifest)
  README.md                                         CHANGED  (directory map)

scripts/validate-telemetry-governance.py            NEW
scripts/tests/test_validate_telemetry_governance.py NEW
.github/workflows/observability-platform-ci.yml     CHANGED  (pyyaml + governance validation)
scripts/observability-stack.sh                      CHANGED  (smoke asserts deny-list stripping)

docs/specs/domains/08-observability-platform/SPEC-OP-003-.../traceability-entry.yaml   CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-003-traceability.md        NEW (this file)
```

## 3. Commands run and results (2026-08-30 UTC)

| Command | Result |
|---|---|
| `python scripts/validate-observability-layout.py` | 0 errors, 0 warnings |
| `uv run --with pyyaml python scripts/validate-telemetry-governance.py` | 0 errors, 0 warnings — structure OK; deny baseline covered; canonical deny regex found in `collector/base/config.yaml` ×6 (resource+span, resource+datapoint, resource+log); retention classes valid; 0 exceptions |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **15 passed** (8 layout + 7 governance: canonical regex, real-file↔collector sync, expired exception → fail, desynced regex → fail, gutted baseline → fail, deny-field waiver w/o domain-06 → fail) |
| `otelcol-contrib validate --config=base.yaml --config=local.yaml` | exit 0 — `transform/governance` OTTL parses |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — pushed an OTLP span carrying `authorization` / `password` / `api_key` (+ legit `correlation_id`); queried Tempo back: span attribute keys = `['correlation_id']` only, **deny-listed present: NONE (stripped)**. Metrics/logs/rules/alertmanager paths still green. |
| `curl /api/traces/<id>` + JSON parse | span attributes in Tempo = `correlation_id` only; `authorization`, `password`, `api_key` deleted by `transform/governance` |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers left |

## 4. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Deny-list is **key-based**, substring, case-insensitive — an over-broad key (e.g. an attribute literally named `tokenizer`) is dropped | Medium | Documented tradeoff (`docs/telemetry-governance.md` §1); the exception workflow gives producers a time-boxed waiver; `SPEC-OP-007` adds precise value-level redaction |
| Value-level PII inside free-text log bodies is **not** scrubbed yet (e.g. an email in a message string) | Medium | Explicitly `SPEC-OP-007` scope; the baseline covers attribute keys only |
| The Collector regex and `deny_fields` are kept in sync by a **string-equality** check, not code-generation | Low | `validate-telemetry-governance.py` fails CI on any divergence; `SPEC-OP-006`+ may generate the processor from the manifest |
| `retention_classes.*.prod` durations are placeholders | Low | `SPEC-OP-015` sets capacity-modelled values in the same PR that updates this file |
| Cardinality budgets are declared but not yet machine-enforced against a live TSDB | Medium | `SPEC-OP-006` adds `promtool`/series-count tooling and a `prometheus_tsdb_head_series` burn alert |
| `exceptions: []` — no waiver has been exercised end to end | Low | self-tests cover the malformed/expired/no-domain-6 paths; first real waiver will exercise the happy path |
| `otp` / `pwd` short patterns could match unrelated keys | Low | rare in attribute keys; covered by the exception workflow; revisit in `SPEC-OP-007` |

## 5. Sign-off

The telemetry governance rulebook exists, is schema-checked, is wired into the
Collector, and is proven at runtime: a signal carrying `authorization` / `password` /
`api_key` reaches the backend with those keys removed and its legitimate attributes
intact. Phase-00 (Platform Engineering Foundation) is complete — `SPEC-OP-001`
(boundaries/layout), `SPEC-OP-002` (local topology), `SPEC-OP-003` (governance
baseline) are all implemented and CI-gated. Phase-01 (`SPEC-OP-004`–`007`, Unified
Signal Contracts) builds producer contracts against `governance/telemetry-governance.yaml`.
