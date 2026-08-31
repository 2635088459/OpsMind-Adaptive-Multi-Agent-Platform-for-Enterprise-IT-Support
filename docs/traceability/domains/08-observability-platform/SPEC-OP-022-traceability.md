# SPEC-OP-022 Traceability — SLI, SLO, And Error Budget Model

> Domain: `08-observability-platform`
> Phase: `phase-05-alerts-slos-runbooks`
> Status: implemented
> Verified: 2026-08-31 (the full formula chain proven with EXACT matching
> arithmetic — 10/11 = 0.909090..., not just "some number appeared")
> Owner: `platform-observability`

## 1. Objective mapping

| Spec area | Where |
|---|---|
| SLI | `slo:http_availability:good_ratio1h` — actual success ratio |
| SLO | `slo:http_availability:target_ratio` = `0.99`, 1h window (local substitute for a 28-30 day real period) |
| Error budget | `slo_error_budget_ratio{slo="http-availability",objective="0.99",window="1h"}` |
| Burn rate | `slo_burn_rate_ratio{...}` |
| Budget-consumption alert | `SloErrorBudgetLow` (warning, <50%, distinct from `SPEC-OP-023`'s fast pages) |

## 2. Files added / changed

```text
infrastructure/observability/
  rules/recording/slo-http-availability.yml   NEW
  rules/alerting/slo-http-availability.yml    NEW
  runbooks/SloErrorBudget.md                  NEW

.github/workflows/observability-platform-ci.yml   CHANGED (2 new rule files)
scripts/observability-stack.sh                    CHANGED (2 new smoke assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-022-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-022-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `promtool check rules` | SUCCESS — 4 recording + 1 alerting |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** (assertions check the rule is wired/query-valid — a single push can't produce a real `rate()` value, same limitation `SPEC-OP-020` already documented) |
| Manual formula-verification (see §4) | `good_ratio1h = 0.909090909...` = exactly `10/11`; `burn_rate_ratio = 9.090909...` = exactly `(1-10/11)/(1-0.99)`; `error_budget_ratio = 0` = exactly `clamp_min(1-9.09,0)` |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Two real mistakes made and fixed during verification

1. **Series split from inconsistent resource attributes.** A first re-push
   attempt dropped `service.version` from the resource attributes. Prometheus's
   `resource_to_telemetry_conversion` folds every resource attribute into the
   metric's label set — dropping one mid-stream doesn't "update" the existing
   series, it creates a **second, unrelated** series. `rate()` on either
   individually saw a flat line and returned `NaN`. Fixed by keeping the resource
   attributes byte-identical across repeated pushes to what should be one logical
   series — obvious in hindsight, non-obvious while debugging a `NaN`.
2. **Recording-rule evaluation-interval timing, distinct from `rate()`'s own
   2-scrape requirement.** Even after fixing #1, the recording rule still read
   `NaN` for another 30-40 seconds. This rule group's `interval: 1m` means the
   STORED value only updates once a minute, regardless of how fresh the
   underlying raw series is. A recording-rule-derived value needs BOTH enough
   raw scrapes for `rate()` (already known from `SPEC-OP-020`) AND the next
   evaluation cycle of the rule group itself — querying the raw sub-expressions
   directly (bypassing the recording rule) proved the data was already correct
   before the rule had caught up, which is what led to isolating this as a
   timing issue rather than a formula bug.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| 1h window is a local substitute for a real 28-30 day SLO period | Low (documented, not hidden) | production topology would use a real rolling window against real long-term storage |
| Only one SLO (`http-availability`) exists | Low | additive — a new SLO is a new recording-rule group following the same pattern |
| `objective: 0.99` is a placeholder, not a negotiated target | Low | real target-setting is a product/domain-owner decision, not this spec's to invent |

## 6. Sign-off

The error-budget/burn-rate formula is proven correct with exact arithmetic, not
merely "produces a plausible-looking number." Two real debugging mistakes are
documented as lessons rather than smoothed over. `SPEC-OP-023` (Multi-Window
Burn-Rate Alerts) builds directly on `slo_burn_rate_ratio` next.
