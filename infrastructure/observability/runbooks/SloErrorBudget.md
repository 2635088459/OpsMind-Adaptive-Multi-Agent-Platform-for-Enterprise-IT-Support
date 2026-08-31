# SloErrorBudget

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-022
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; promtool check rules; recreate prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-022-traceability.md

Covers `SloErrorBudgetLow` (`rules/alerting/slo-http-availability.yml`) and the
underlying SLO/error-budget model (`rules/recording/slo-http-availability.yml`).

## The model

**Objective**: 99% of HTTP requests succeed (non-5xx), evaluated over a 1h window
locally (a real production deployment would use a 28-30 day rolling window — 72h
local retention cannot hold that much data, so this is a deliberately scaled-down
local substitute, not the real target).

- `slo:http_availability:good_ratio1h` — the actual success ratio over the window.
- `slo_burn_rate_ratio{slo="http-availability",objective="0.99",window="1h"}` —
  how many multiples of the SLO's own error rate you are currently consuming.
  `1.0` = burning exactly on-budget; `>1.0` = burning faster than sustainable;
  `<1.0` = better than the objective.
- `slo_error_budget_ratio{...}` — how much of the window's error budget remains,
  `clamp`ed at `0` (never negative — a fully-exhausted budget stays at `0`, it
  does not go "more exhausted").

These are the canonical `slo.*` namespace metric names `SPEC-OP-006` already
contracted (`signals/metric-naming.yaml`) — this spec is the first to actually
populate them.

## Impact

**Leading indicator, not yet a page.** `SloErrorBudgetLow` fires at 50% budget
consumption — a trend worth investigating before it becomes a fast-burn page
(`SPEC-OP-023`).

## Detection

- `slo_error_budget_ratio{slo="http-availability"}` directly, or the Golden Path
  dashboard (`SPEC-OP-016`) — same underlying `http.*` series.

## Triage

1. Check `http:error_ratio:rate5m` (`SPEC-OP-020`) — is this a sustained low-grade
   elevation, or a series of short spikes each individually below
   `HighRequestErrorRate`'s 5% threshold but adding up over the window?
2. Cross-check `service_namespace`/`service_name` breakdowns to find which
   specific service is consuming the budget.

## Mitigation

Same triage path as `HttpGoldenSignals.md` — this is the same underlying signal,
viewed as cumulative consumption rather than an instantaneous threshold.

## Resolution

`slo_error_budget_ratio{slo="http-availability"}` back above 50%.

## Rollback

`git revert` the offending rule/config change; `promtool check rules`; recreate
`prometheus`.

## Escalation

The owning domain's on-call, same as `HttpGoldenSignals.md` — domain 08 defines
the objective and computes the budget, it does not remediate it (ADR-0004).

## Post-incident

If 99%/1h consistently proves too strict or too loose for real traffic, that is
real input for retuning the `objective` value — a deliberate, reviewed change,
not something to silently adjust to make the alert stop firing.
