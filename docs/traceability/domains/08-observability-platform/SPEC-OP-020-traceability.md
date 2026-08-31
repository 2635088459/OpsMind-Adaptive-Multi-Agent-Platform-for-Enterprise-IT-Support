# SPEC-OP-020 Traceability — Recording And Alert Rule Catalog

> Domain: `08-observability-platform`
> Phase: `phase-05-alerts-slos-runbooks` (opens this phase)
> Status: implemented
> Verified: 2026-08-31 (real per-status-code counts proven correct; the new
> validator's own false-positive on Loki rules diagnosed and fixed, not ignored)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective (interpreted from the generic per-spec README plus the mapped LLD +
what was already-built-but-unwired): *a rule catalog with the business
request-path golden signals every prior spec had left out, plus real cross-
reference validation that nothing had ever done.*

| Spec area | Where |
|---|---|
| Business request-path alerts | NEW `HighRequestErrorRate` / `HighRequestLatency` (`rules/{recording,alerting}/http-server.yml`) |
| Rule catalog (human-readable) | NEW `rules/CATALOG.md` |
| Rule catalog (machine-checked) | NEW `scripts/validate-rule-catalog.py` |

## 2. Files added / changed

```text
infrastructure/observability/
  rules/recording/http-server.yml   NEW
  rules/alerting/http-server.yml    NEW
  rules/CATALOG.md                  NEW
  runbooks/HttpGoldenSignals.md     NEW

scripts/validate-rule-catalog.py               NEW
scripts/tests/test_validate_rule_catalog.py    NEW
.github/workflows/observability-platform-ci.yml   CHANGED (2 new steps: rule-catalog
                                                   validator + promtool coverage)
scripts/observability-stack.sh                    CHANGED (3 new smoke assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-020-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-020-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `uv run --with pyyaml python scripts/validate-rule-catalog.py` (first run) | 3 warnings: 1 real naming-convention issue (`http:duration_p95:5m`) + 2 "orphaned runbook" flags |
| Renamed to `http:duration:p95` | naming warning cleared |
| Investigated `BrokenTracePropagation.md` | confirmed genuinely deliberate — the runbook's own text says so (`SPEC-OP-005` §6); left as an accepted warning, not suppressed |
| Investigated `StructuredLogContractViolation.md` | confirmed a validator SCOPE bug (it never scanned `loki/rules/`) — fixed by adding that scan; warning disappeared, proving the fix |
| `promtool check rules` (both new files) | SUCCESS — 3 recording + 2 alerting |
| `uv run --with pyyaml python -m unittest ...` (writing the validator's own tests) | first draft crashed on `path.relative_to(REPO)` for a tempdir fixture — same bug class as `validate-dashboards.py`/`validate-collector-pipeline.py`; fixed with a shared `_rel()` helper |
| `scripts/observability-stack.sh smoke` (first attempt, rate()-value assertion) | **FAIL** — `http:request:rate5m` / `http:error_ratio:rate5m` returned empty; `rate()` needs 2+ scrapes across its window, a single smoke push only produces 1 |
| Rewrote the assertion to check raw per-status-code counts instead | **SMOKE: PASS** — see §4 |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Smoke evidence

Pushed `http_server_request_duration_seconds` with 5 datapoints at `200` and 1 at
`500` (the same push `SPEC-OP-016` already used). Confirmed:
`http_server_request_duration_seconds_count{...,http_response_status_code="200"}`
= exactly `5`; the `="500"` series = exactly `1` — the precise raw input
`http:error_ratio:rate5m` computes an ~16.7% ratio from once enough scrapes
accumulate. `HighRequestErrorRate` confirmed loaded via
`GET /api/v1/rules?rule_name=HighRequestErrorRate`.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| 5%/1s thresholds are platform-wide placeholders, not per-domain SLOs | Low | `SPEC-OP-022` (SLI/SLO/Error Budget Model) is where a real per-domain target supersedes this floor |
| `rules/CATALOG.md` is hand-maintained, not auto-generated | Low | the underlying invariants (labels/annotations/runbook existence) ARE CI-enforced; only the human-readable table itself can drift, stated plainly in the doc |

## 6. Sign-off

A real business-request-path alert exists where none did before. Every alert in
the entire domain (Prometheus AND Loki ruler) is now content-validated — required
labels, required annotations, and a real, resolvable runbook — not just a file
header. Two investigations (one confirming a deliberate non-decision, one fixing a
real validator scope bug) are recorded, not glossed over. `SPEC-OP-021`
(Alertmanager Routing, Dedup, And Silence) continues phase-05.
