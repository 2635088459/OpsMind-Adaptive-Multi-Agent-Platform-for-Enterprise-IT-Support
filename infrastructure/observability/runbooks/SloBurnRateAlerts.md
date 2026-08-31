# SloBurnRateAlerts

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-023
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; promtool check rules; recreate prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-023-traceability.md

Covers `SloFastBurnPage`, `SloSlowBurnPage`, `SloSlowBurnTicket`,
`SloSlowBurnTicketLong` (`rules/alerting/slo-burn-rate-multiwindow.yml`) — the
standard [Google SRE multi-window burn-rate table](https://sre.google/workbook/alerting-on-slos/)
for the `http-availability` SLO `SPEC-OP-022` defines.

## The 4-tier table

| Alert | Long window | Short window | Threshold | Budget in window | Severity |
|---|---|---|---|---|---|
| `SloFastBurnPage` | 1h | 5m | 14.4x | 2% | critical (page) |
| `SloSlowBurnPage` | 6h | 30m | 6x | 5% | critical (page) |
| `SloSlowBurnTicket` | 1d | 2h | 3x | 10% | warning (ticket) |
| `SloSlowBurnTicketLong` | 3d | 6h | 1x | 10% | warning (ticket) |

Each alert requires **both** its long and short window to exceed the threshold —
the short window makes the alert fast to fire on a genuine sustained problem, the
long window makes it resistant to a single brief blip in the short window alone.

## Impact

**Real business impact**, same signal as `HttpGoldenSignals.md`
(`HighRequestErrorRate`/`HighRequestLatency`) and `SloErrorBudget.md`
(`SloErrorBudgetLow`) — this is the same underlying `http.*` data, viewed through
a burn-RATE lens (how fast is the budget being consumed) rather than an
instantaneous threshold or a slow cumulative view.

## Detection

`slo_burn_rate_ratio{slo="http-availability", window=...}` for the 6 windows
(`SPEC-OP-022`'s `1h`, plus `SPEC-OP-023`'s `5m`/`30m`/`2h`/`6h`/`1d`/`3d`).

## Triage

1. **`SloFastBurnPage`**: same first move as `HighRequestErrorRate` — check
   Saturation, then trace drilldown for a representative failing request.
2. **`SloSlowBurnPage`**: a real but slower-developing version of the same thing
   — look for a gradual degradation trend (e.g. a slow memory leak, a growing
   queue) rather than a sudden spike.
3. **`SloSlowBurnTicket`/`SloSlowBurnTicketLong`**: not page-urgent. File/pick up
   as a ticket; investigate on the next business day unless it correlates with
   something already known.

## Mitigation

Same triage path as `HttpGoldenSignals.md` and `SloErrorBudget.md` — all three
runbooks look at the same underlying `http.*` series from different angles
(instantaneous threshold, cumulative budget, burn rate). Fixing the root cause
resolves all three simultaneously.

## Resolution

All 4 alerts' `slo_burn_rate_ratio` window pairs back under their thresholds.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate
`prometheus`.

## Escalation

The owning domain's on-call for `SloFastBurnPage`/`SloSlowBurnPage` (paging
severity); a ticket queue for the two ticket-tier alerts. Domain 08 defines and
computes the model, it does not remediate (ADR-0004).

## Post-incident

If a specific threshold/window pair proves consistently too noisy or too quiet
for real traffic, that is real input for retuning it — a deliberate, reviewed
change to this file, never a silent adjustment to make an alert stop firing.

## Known local-scale limitation

Local retention is 72h (=3d). `SloSlowBurnTicketLong`'s `3d`/`6h` window pair is
correct by formula and `promtool`-valid, but can only ever evaluate meaningfully
against 3+ real days of continuous local data — it was not live-fired in this
spec's own smoke verification (see the traceability doc for exactly which window
pair WAS live-verified).
