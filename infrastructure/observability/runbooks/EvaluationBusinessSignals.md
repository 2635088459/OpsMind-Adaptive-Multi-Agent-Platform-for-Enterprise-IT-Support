# EvaluationBusinessSignals

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-028
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-028-traceability.md

Covers both alerts SPEC-OP-028 adds: `EvaluationGateFailureRateHigh`
(`evaluation` namespace) and `GraderErrorRateHigh` (`grader` namespace) —
both from evaluation-improvement-service's real `EvaluationTelemetry` — one
file per this spec's own incident class, same grouping convention as this
domain's other business-signal runbooks.

## Impact

- `EvaluationGateFailureRateHigh`: release gates are correctly blocking
  candidates that don't meet quality thresholds — this is the safety
  mechanism working as designed, not a business outage. Impact is on
  release velocity (fewer candidates progress), not on production traffic.
- `GraderErrorRateHigh`: the evaluation harness itself cannot judge
  candidates — no gate, regression check, or candidate decision can proceed
  until the grader works again. This blocks the whole evaluation/improvement
  pipeline, not just one candidate.

## Detection

- Firing expressions:
  - `evaluation:gate_fail:rate5m > 0` for 10m
    (`sum(rate(evaluation_gate_fail_total[5m]))`)
  - `grader:error:rate5m > 0` for 5m
    (`sum(rate(grader_error_total[5m]))`)
- Dashboard: `dashboards/evaluation-business-signals.json` ("Evaluation
  Business Signals")
- Correlation entry point: filter the dashboard's log panel by
  `service_namespace: evaluation-improvement` and the relevant `trace_id`.

## Triage

1. Check which alert fired — they have unrelated root causes.
2. For `EvaluationGateFailureRateHigh`: break down
   `evaluation_gate_fail_total` by `gate_policy` and `reason` to see whether
   one gate policy or one failure reason dominates — a single dominant
   reason usually points at one recent regression, not a systemic issue.
3. For `GraderErrorRateHigh`: break down `grader_error_total` by
   `grader_type`/`grader_version` to see whether one grader version is
   responsible (a bad grader deploy) or the failure is broad-based (an
   upstream dependency the grader itself relies on, e.g. an LLM provider).

## Mitigation

- `EvaluationGateFailureRateHigh`: no direct mitigation from this side — a
  correctly-firing gate is not an incident to suppress; if the gate policy
  itself is miscalibrated, that is domain 07's own policy-tuning call, not
  something this runbook instructs from the observability side.
- `GraderErrorRateHigh`: no direct mitigation from this side either — if a
  specific grader version needs to be rolled back, that is domain 07's own
  deployment call ([forbidden-business-writes
  §4](../docs/forbidden-business-writes.md)).

## Resolution

- `EvaluationGateFailureRateHigh`: durable fix is domain 07's — either a
  fixed regression in the candidate under test, or a corrected gate policy if
  it was miscalibrated. Confirm resolution by watching
  `evaluation:gate_fail:rate5m` return to its historical baseline.
- `GraderErrorRateHigh`: durable fix is domain 07's — a restored grader
  version or a resolved upstream dependency. Confirm resolution by watching
  `grader:error:rate5m` return to `0`.

## Rollback

Exact revert: `git revert <sha>` on this runbook / the two rule files
(`rules/recording/evaluation-business.yml`,
`rules/alerting/evaluation-business.yml`); `promtool check rules`; recreate
Prometheus. Reverting this spec only removes the ALERT — it does not touch
evaluation-improvement-service's own metrics code.

## Escalation

- `EvaluationGateFailureRateHigh` (`warning`): opens a ticket against
  evaluation-improvement-service's on-call
  (`service_namespace: evaluation-improvement`) — domain 08 defines and
  detects the signal, it does not remediate it (ADR-0004).
- `GraderErrorRateHigh` (`critical`, paging): pages
  evaluation-improvement-service's on-call directly — a blocked evaluation
  pipeline is urgent enough to page, not queue as a ticket.

## Post-incident

Link the traceability entry
(`docs/traceability/domains/08-observability-platform/SPEC-OP-028-traceability.md`).
Residual risk: this spec only contracts and alerts on the subset of
evaluation-improvement-service's already-emitted metrics most directly tied
to business-visible failure (`evaluation_gate_fail_total`,
`grader_error_total`) — the other 11 already-real metrics across 6
namespaces (`evaluation`, `improvement`, `canary`, `grader`, `judge`,
`online`) are now contracted (bounded labels enforced) but have no dedicated
alert yet; a follow-up spec could add more if a real operational need
surfaces.
