# SPEC-OP-023 Traceability — Multi-Window Burn-Rate Alerts

> Domain: `08-observability-platform`
> Phase: `phase-05-alerts-slos-runbooks`
> Status: implemented
> Verified: 2026-08-31 (real sustained ~83.33x burn rate, all 4 alerts confirmed
> transitioning to "pending" via Prometheus's own `/api/v1/rules` API)
> Owner: `platform-observability`

## 1. Objective mapping

| Spec area | Where |
|---|---|
| Multi-window recording rules | `slo:http_availability:good_ratioXXX` / `slo_burn_rate_ratio{window="XXX"}` for XXX in `5m,30m,1h,2h,6h,1d,3d` (1h already existed from `SPEC-OP-022`; this spec adds the other 6) |
| Fast burn / page (2% budget / 1h) | `SloFastBurnPage` — critical, 1h+5m windows both >14.4x, `for: 2m` |
| Slow burn / page (5% budget / 6h) | `SloSlowBurnPage` — critical, 6h+30m windows both >6x, `for: 15m` |
| Slow burn / ticket (10% budget / 1d) | `SloSlowBurnTicket` — warning, 1d+2h windows both >3x, `for: 1h` |
| Slow burn / ticket, long (10% budget / 3d) | `SloSlowBurnTicketLong` — warning, 3d+6h windows both >1x, `for: 3h` |

Standard Google SRE 4-tier table (https://sre.google/workbook/alerting-on-slos/),
distinct from `SPEC-OP-022`'s `SloErrorBudgetLow` (single-window, slow
cumulative-consumption signal, not a fast page).

## 2. Files added / changed

```text
infrastructure/observability/
  rules/recording/slo-burn-rate-multiwindow.yml   NEW
  rules/alerting/slo-burn-rate-multiwindow.yml    NEW
  runbooks/SloBurnRateAlerts.md                   NEW
  rules/CATALOG.md                                CHANGED (new row)

.github/workflows/observability-platform-ci.yml   CHANGED (2 new rule files in promtool check rules)
scripts/observability-stack.sh                     CHANGED (6-window + 4-alert smoke assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-023-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-023-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `promtool check rules` (both new files) | SUCCESS — 12 recording + 4 alerting |
| `validate-observability-layout.py` | 0 errors (3 expected `audit_ref` warnings, cleared by this file) |
| `validate-rule-catalog.py` | 0 errors (10 expected warnings — naming-convention exceptions for `slo_burn_rate_ratio` ×6, same documented exception as `SPEC-OP-022`, plus 2 already-known orphaned-runbook warnings) |
| `python -m unittest discover -s scripts/tests` | 76 passed |
| Real sustained burn-rate push (~140s background script) | produced a real, continuous ~83.33x burn rate across all 6 windows on one logical series |
| `GET /api/v1/rules?rule_name=SloFastBurnPage` etc. (before fix) | all 4 alerts stayed **"inactive"** despite real over-threshold data — this is what proved the bug below was real |
| `POST /-/reload` (Prometheus hot-reload, `--web.enable-lifecycle` already on) | applied the `ignoring(window)` fix without a container recreate |
| `GET /api/v1/rules?rule_name=X` (after fix) | `SloFastBurnPage`, `SloSlowBurnPage`, `SloSlowBurnTicket`, `SloSlowBurnTicketLong` all **"pending"**; `SloErrorBudgetLow` also "pending" |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — loops over all 6 windows checking query-validity, loops over the 4 alert names checking each is loaded; every `SPEC-OP-002~022` assertion in the same run stayed green |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |
| Independent second re-verification (fresh stack, same day) — see §4a | all 7 windows (`5m,30m,1h,2h,6h,1d,3d`) read exactly `79.99999999999993`; all 5 SLO alerts (`SloFastBurnPage`/`SloSlowBurnPage`/`SloSlowBurnTicket`/`SloSlowBurnTicketLong`/`SloErrorBudgetLow`) confirmed `pending` via `GET /api/v1/rules?type=alert`, values matching exactly |

## 4. Real bug found and fixed: PromQL `and` label-matching

**Symptom:** even with a sustained, real 83.33x burn rate (5.8x over the
14.4x threshold) held across every window for over two minutes, all 4 alerts
stayed "inactive" — never even "pending".

**Root cause:** every alert's original `expr` compared two windows of the
same metric like this:

```promql
slo_burn_rate_ratio{slo="http-availability",window="1h"} > 14.4
and slo_burn_rate_ratio{slo="http-availability",window="5m"} > 14.4
```

PromQL's default `and` binary operator vector-matches **on all labels** of
both operand vectors before combining them. Both operands here are the same
metric name with the same `slo` label, but a **different** `window` label —
which is the entire point of comparing two windows. Because `window` differs,
the default matcher can never find a corresponding series on the other side,
so the `and` always evaluates to an empty vector, and the alert can never
fire, regardless of how far over threshold the real data is.

**Fix:** add `ignoring(window)` to the second operand of every alert, so
vector matching ignores the one label the two operands are deliberately
supposed to differ on:

```promql
slo_burn_rate_ratio{slo="http-availability",window="1h"} > 14.4
and ignoring(window) slo_burn_rate_ratio{slo="http-availability",window="5m"} > 14.4
```

Applied to all 4 alerts, each with its own window pair (1h/5m, 6h/30m,
1d/2h, 3d/6h).

**Secondary fix (same file, found while re-reading annotations):** the
summary annotation used `{{ $value | printf "%.1f" }}x` — `printf` is not a
function in Prometheus's template `FuncMap`, so this would have failed to
render at alert time. Fixed to `{{ $value | humanize }}x`.

**Why this matters beyond this one file:** any future alert or recording
rule in this domain that compares the *same* metric across two different
label values (not just `window`) needs the same `ignoring(<label>)` (or
`on(<labels>)`) treatment — this is now the second time in this domain a
label-matching semantics issue has produced a silent no-op rather than an
error (`promtool check rules` and `otelcol validate` both passed the whole
time; the bug was only visible by pushing real data past the threshold and
watching the alert not fire).

## 4a. Independent re-verification (second real run, same-day)

The `ignoring(window)` fix was re-proven end-to-end a second time, from a
freshly re-created stack, to confirm the result wasn't a fluke of the first
run's specific timing:

- Brought the stack up clean (`scripts/observability-stack.sh up`), pushed a
  sustained OTLP histogram stream via a Python script (stdlib `urllib`, no
  extra deps) sending +2 success / +8 failure every 5s for 3 minutes.
- **Caught a real bug in the verification script itself**: the first version
  re-sent the *same* cumulative count (`2`, `8`) on every push instead of a
  monotonically increasing total. Cumulative-temporality OTLP requires each
  point to report the running total since start — repeating the same value
  makes the counter look flat, so `rate()` legitimately returned `0/0 = NaN`
  on every window, indistinguishable at first glance from the alert logic
  being broken again. Confirmed the raw `sum(rate(...[5m]))` was itself `0`
  before touching any recording-rule file, which is what pointed at the
  script rather than the rule. Fixed by accumulating `cum_success`/
  `cum_failure` across iterations before each push.
- After the fix, watched `slo_burn_rate_ratio` transition from `NaN` to a
  real value per window as each recording-rule group's own `interval: 1m`
  tick landed (visible directly via
  `time() - prometheus_rule_group_last_evaluation_timestamp_seconds` — the
  `slo-burn-rate-multiwindow-recording` group's next tick was ~19s out when
  first checked, confirming this was the same recording-rule cadence lag
  already documented in `SPEC-OP-022`, not a new problem).
- **Final confirmed state, all 7 windows simultaneously**: `5m`, `30m`, `1h`,
  `2h`, `6h`, `1d`, `3d` all read exactly `79.99999999999993` — matching the
  exact formula arithmetic for a sustained 20% success ratio against a 99%
  objective: `good_ratio = 2/(2+8) = 0.2`; `burn_rate = (1-0.2)/(1-0.99) = 80`.
  `GET /api/v1/rules?type=alert` confirmed all 5 SLO alerts in `pending`:
  `SloFastBurnPage` (window `1h`, value `80`), `SloSlowBurnPage` (window
  `6h`, value `80`), `SloSlowBurnTicket` (window `1d`, value `80`),
  `SloSlowBurnTicketLong` (window `3d`, value `80`), `SloErrorBudgetLow`
  (value `0` — budget fully consumed at an 80x burn rate, exactly
  `clamp_min(1-80,0)`).
- This is a distinct real bug from the `ignoring(window)` one — found in
  ad-hoc verification tooling, not in a committed rule file — but recorded
  here rather than silently fixed and dropped, per this domain's standing
  practice of not glossing over real mistakes made while proving a spec.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| 3d/6h window (`SloSlowBurnTicketLong`) needs 72h+ of real retention to ever meaningfully evaluate in this local topology (72h local retention ceiling) | Low (documented in the runbook, not hidden) | production topology with real long-term storage would have this window naturally covered; local verification instead proved the formula/matching logic correct via a compressed, artificially-sustained burn |
| All 4 thresholds/windows are the textbook Google SRE defaults, not re-derived for this platform's actual traffic/SLO history | Low | acceptable starting point; re-tuning is a data-driven follow-up, not a correctness gap |
| Only one SLO (`http-availability`) has burn-rate coverage | Low | additive — a new SLO is a new recording-rule group following the same pattern established here and in `SPEC-OP-022` |

## 6. Sign-off

The 4-tier multi-window burn-rate alert table is implemented and proven
correct against a real, sustained, over-threshold burn — not merely
"syntactically valid" (which is all `promtool check rules` alone would have
confirmed, and which is exactly what silently passed while the alerts were
actually broken). A real PromQL vector-matching bug was found the hard way,
understood, fixed, and documented as a reusable lesson. This closes the
alerting-logic portion of `phase-05`; `SPEC-OP-024` (Operational Runbook
Catalog) remains to close the phase itself.
