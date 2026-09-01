# SPEC-OP-028 Traceability — Evaluation Observability Contract

> Domain: `08-observability-platform`
> Phase: `phase-06-cross-domain-contracts`
> Status: implemented
> Verified: 2026-09-01 (rebuilt after a mid-session data-loss incident — see SPEC-OP-025's own §5)
> Owner: `platform-observability`

## 1. Objective mapping

| Requirement | Where |
|---|---|
| Evaluation/grading/regression/canary signals | `metric-naming.yaml` — `evaluation` (CORRECTED), `improvement`, `canary`, `grader`, `judge`, `online` namespaces — 6 real prefixes from ONE class |
| Query/dashboard artifact | `dashboards/evaluation-business-signals.json` |
| Rule/runbook artifact | `rules/{recording,alerting}/evaluation-business.yml` + `runbooks/EvaluationBusinessSignals.md` |

## 2. Real finding: a SECOND fictional metric-naming namespace, plus a 6-way prefix split

The EXISTING `evaluation` namespace (since `SPEC-OP-006`) had the same
defect `SPEC-OP-026` already found in "agent" — `allowed_labels`/
`example_metrics` never matched anything real (zero-match grep). This is
the SECOND time this exact class of defect has been found in this domain's
own contract — worth remembering as a recurring risk in any namespace old
enough to predate this domain's "verify against real code first"
discipline.

`EvaluationTelemetry`'s real 13 metrics span SIX distinct prefixes:
`evaluation_*` (8), `improvement_*`, `canary_*`, `grader_*`, `judge_*`,
`online_*` (1 each). Six namespaces contracted — one corrected, five new.

## 3. Real finding: a bare, LLD-mandated metric name needing a new suffix

`evaluation_score` has no unit suffix — confirmed deliberate (not an
oversight) by reading the class's own docstring, citing `SPEC-EI-033` and
`12-observability §Metrics` by name. Added `_score` to
`naming.allowed_suffixes`, distinguished explicitly from the millisecond
case documented as a genuine violation under `SPEC-OP-026`.

## 4. Severity reasoning, made explicit

`EvaluationGateFailureRateHigh` is `warning`: a release gate correctly
rejecting a bad candidate is the safety mechanism working as designed —
same reasoning behind not alerting on `canary_rollback_total` at all (also
a safety net working correctly, visible on the dashboard only).
`GraderErrorRateHigh` is `critical`/paging: a broken grader blocks the WHOLE
pipeline from judging any candidate — infrastructure genuinely broken. Same
distinction `SPEC-OP-026` already applied
(`MemoryEmbeddingProviderFailing` vs. `AgentRuntimeTaskLeaseExpiredHigh`).

## 5. Real docker-compose verification (2026-09-01, second build)

Pushed real OTLP metrics, each with one forbidden label riding along:
`evaluation_gate_fail_total{gate_policy="regression_gate", case_id="SHOULD-
BE-STRIPPED"}=4`; `grader_error_total{grader_type="llm_judge",
run_id="SHOULD-BE-STRIPPED"}=6`. Confirmed exact raw counts (4, 6) reached
Prometheus while neither forbidden label did; both new recording rules
query-valid; both new alerts loaded. Every `SPEC-OP-002~029` assertion in
the same run stayed green.

## 6. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Only 2 of the ~13 total newly-contracted metrics have a dedicated alert | Low | additive follow-up |
| `canary_rollback_total` has no alert at all (deliberate) | Low | visible on the dashboard; a real threshold could justify one later |

## 7. Sign-off

A second real, previously-uncontracted metric-naming defect found and
corrected, alongside 5 new real namespaces. A real, LLD-mandated bare metric
name needed a new suffix — added narrowly and proven with a real fixture.
Alert severity assigned with explicit, recorded reasoning consistent with
this domain's established pattern. Rebuilt faithfully after data loss,
re-verified live.
