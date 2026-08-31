# SPEC-OP-006 Traceability — Metric Naming And Cardinality Contract

> Domain: `08-observability-platform`
> Phase: `phase-01-unified-signal-contracts`
> Status: implemented
> Verified: 2026-08-30 (validators pass; smoke proves label stripping; rules evaluate; stack torn down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Define metric names, units, types, buckets, label allowlists, and
per-service series budgets.*

| Spec area | Where |
|---|---|
| Names | `signals/metric-naming.md` §1 + `.yaml` `naming.name_pattern` (`^[a-z][a-z0-9_]*[a-z0-9]$`), `namespaces` (http/amqp/db/runtime/agent/evaluation/slo/otelcol), `allowed_suffixes` |
| Units | §2 + `.yaml` `units.allowed` (seconds/bytes/ratio/1/usd/tokens) + `forbidden_substrings` (`_ms _us _ns _millis _kb _mb _gb _percent`) |
| Types → suffix | §3 + validator: counter→`_total`, histogram base ≠ `_total` |
| Histogram buckets | §4 + `.yaml` `bucket_sets` (`latency_seconds`, `payload_bytes`, `llm_tokens`, `cost_usd`); native histograms allowed with the classic set as fallback |
| Label allow-lists | §5 + `.yaml` `namespaces[].allowed_labels` / `forbidden_labels` / `max_label_keys`, per the 8 namespaces |
| Per-service series budgets | §6 + `.yaml` `service_series_budgets` (`service.namespace` → local/ci/prod), summing under `governance.cardinality_budgets.global.max_series_total` (250 000) |
| governance alignment | `governance/telemetry-governance.yaml` v1.2.0 — added `db`, `runtime`, `slo` cardinality namespaces; `validate-signal-contracts.py` fails if `max_label_keys` / `max_series` / `forbidden_labels` diverge |
| enforcement | Collector `transform/metric-cardinality` (metrics pipeline only) deletes forbidden id/hash/path label keys from metric resource + datapoint attributes; regex = anchored union of every namespace's `forbidden_labels` in governance |
| "schema plus signal-contract tests against Java and Python fixtures" | `signals/fixtures/metric-naming/` — `conformant-http-histogram.json`, `conformant-agent-counter.json` (pass); `nonconformant-bad-unit.json`, `nonconformant-forbidden-label.json`, `nonconformant-not-snake-case.json` (reject); checked by `validate-signal-contracts.py` |
| rules + runbook (owner + version) | `rules/recording/cardinality.yml` (`job:series:count`, `series:count:total`); `rules/alerting/cardinality.yml` (`MetricSeriesBudgetExceeded`, `HighCardinalityJob`, `ForbiddenMetricLabel`); `runbooks/MetricCardinalityBudget.md` |
| CI gate | `layout` job runs `validate-signal-contracts.py` (now also metric-naming) + self-tests; `config` job `promtool check`s the new rules + `otelcol validate`s the new processor; `smoke` job asserts a forbidden label is stripped |
| Traceability | this file + `traceability-entry.yaml` |

Deferred: producer-side metric SDK views (domain teams / SPEC-OP-025+); real
production series budgets + threshold tuning (SPEC-OP-012); native-histogram bucket
schema pinning (SPEC-OP-012); exemplar wiring end to end (SPEC-OP-016 Golden Path).

## 2. Files added / changed

```text
infrastructure/observability/
  signals/metric-naming.md                                     NEW
  signals/metric-naming.yaml                                   NEW
  signals/fixtures/metric-naming/conformant-http-histogram.json         NEW
  signals/fixtures/metric-naming/conformant-agent-counter.json          NEW
  signals/fixtures/metric-naming/nonconformant-bad-unit.json            NEW
  signals/fixtures/metric-naming/nonconformant-forbidden-label.json     NEW
  signals/fixtures/metric-naming/nonconformant-not-snake-case.json      NEW
  schemas/metric-naming.schema.json                            NEW
  rules/recording/cardinality.yml                              NEW
  rules/alerting/cardinality.yml                               NEW
  runbooks/MetricCardinalityBudget.md                          NEW
  collector/base/config.yaml                                   CHANGED  (transform/metric-cardinality + metrics-pipeline wiring)
  governance/telemetry-governance.yaml                         CHANGED  (v1.2.0: db/runtime/slo cardinality namespaces)

scripts/validate-signal-contracts.py                           CHANGED  (metric-naming contract + governance-sync + fixtures + collector-wiring)
scripts/tests/test_validate_signal_contracts.py               CHANGED  (7 new metric-naming tests)
scripts/observability-stack.sh                                CHANGED  (smoke: metric with ticket_id/run_id labels)
.github/workflows/observability-platform-ci.yml               CHANGED  (promtool checks cardinality rules)

docs/specs/domains/08-observability-platform/SPEC-OP-006-.../traceability-entry.yaml   CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-006-traceability.md        NEW (this file)
```

## 3. Commands run and results (2026-08-30 UTC)

| Command | Result |
|---|---|
| `python scripts/validate-observability-layout.py` | 0 errors (4 warnings: `audit_ref` for this file — cleared on commit) |
| `uv run --with pyyaml python scripts/validate-telemetry-governance.py` | 0 errors, 0 warnings (governance v1.2.0 consistent) |
| `uv run --with pyyaml python scripts/validate-signal-contracts.py` | 0 errors, 0 warnings — metric-naming shape OK; every governance cardinality namespace present with matching budgets; 2 pass + 3 reject fixtures behave; `transform/metric-cardinality` wired into the metrics pipeline and its regex covers every governance forbidden label |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **33 passed** (8 layout + 7 governance + 18 signal-contracts — incl. 7 metric-naming: conformant counter, bad-unit rejected, forbidden-label rejected, CamelCase rejected, broken-fixture → fail, `transform/metric-cardinality` unwired → fail, governance-namespace desync → fail) |
| `docker run … otelcol-contrib:0.116.1 validate` | exit 0 (`transform/metric-cardinality` OTTL parses) |
| `promtool check rules …/cardinality.yml` | SUCCESS — 2 recording + 3 alerting rules |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — pushed `op_002_smoke_total` with datapoint attributes `case=smoke`, `ticket_id=INC-9999`, `run_id=r-abc123`. Prometheus series = `{case="smoke", deployment_environment="local", host_name=…, service_name="op-002-smoke", …}` — **no `ticket_id` / `run_id`**. `job:series:count` recording rule evaluated (6 series). `ForbiddenMetricLabel` / `MetricSeriesBudgetExceeded` / `HighCardinalityJob` all `inactive`. SPEC-OP-003/004/005 assertions still green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `transform/metric-cardinality` is a **fixed deny regex**, not "keep only allow-listed labels" (RE2 has no lookahead) | Medium | catches every id/hash/path label class governance knows; a genuinely new unbounded label class needs a one-line PR to governance + the regex; `HighCardinalityJob` + `ForbiddenMetricLabel` alerts catch what slips through at runtime |
| Per-service series budgets are estimates, not measured | Low | `SPEC-OP-012` sets real production numbers against a loaded TSDB; `series:count:total` recording rule makes the actuals visible now |
| Alert thresholds (250 000 global, 20 000 per job) are laptop-scale | Low | `SPEC-OP-012` / `SPEC-OP-022` tune for production |
| Name/unit/type/label rules are checked against **fixtures**, not a live scrape | Medium | `SPEC-OP-025`+ bind each service's real `/metrics` (or OTLP) to these fixtures; a `promtool check metrics` lint on a captured scrape can be added |
| Native-histogram bucket schema not pinned | Low | `SPEC-OP-012` pins `scrape_config` `native_histogram_bucket_limit`; classic fallback buckets are defined here |
| `otelcol` self-metrics carry labels like `receiver`, `exporter` — allow-listed, but their value sets grow with config | Low | bounded by the number of configured components; `otelcol` namespace `max_series` 10 000 |

## 5. Sign-off

Metric names, units, types, histogram bucket sets, per-namespace label allow-lists, and
per-service series budgets are defined (human + machine + schema), aligned with the
governance rulebook (v1.2.0), and enforced: the Collector strips forbidden per-request
label keys on the metrics pipeline, three cardinality alerts + two recording rules are
live, and the smoke test proves a metric carrying `ticket_id` / `run_id` reaches
Prometheus with those labels removed. `SPEC-OP-007` (Structured Log And Redaction
Contract) is the last spec in phase-01.
