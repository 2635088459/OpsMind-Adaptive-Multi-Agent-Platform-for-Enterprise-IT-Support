# SPEC-OP-026 Traceability — Runtime And Memory Observability Contract

> Domain: `08-observability-platform`
> Phase: `phase-06-cross-domain-contracts`
> Status: implemented
> Verified: 2026-09-01 (rebuilt after a mid-session data-loss incident — see SPEC-OP-025's own §5)
> Owner: `platform-observability`

## 1. Objective mapping

| Requirement | Where |
|---|---|
| Agent-runtime signals, bounded labels | `metric-naming.yaml` `agent` namespace, CORRECTED to real metrics (16 from `RuntimeTelemetry`, SPEC-ARO-034) |
| Memory/knowledge signals, bounded labels | `metric-naming.yaml` `memory` (12 metrics) + `knowledge` (2 metrics) namespaces, real from `MemoryTelemetry` (SPEC-MK-028) |
| Query/dashboard artifact | `dashboards/runtime-memory-business-signals.json` |
| Rule/runbook artifact | `rules/{recording,alerting}/runtime-memory-business.yml` + `runbooks/RuntimeMemoryBusinessSignals.md` |

## 2. Real finding #1: agent-runtime-service was never actually a gap

This domain's own memory had flagged agent-runtime-service as a possible
gap, based on a grep for Java-style patterns (`Counter`, `MeterRegistry`)
that found zero matches. Re-investigating with search terms appropriate to
the service's actual language (Python,
`opentelemetry.metrics.get_meter(...).create_counter`) found a real, fully-
wired `RuntimeTelemetry` (SPEC-ARO-034) with 16 real metrics. The earlier
finding was a tooling mismatch, not a real gap.

## 3. Real finding #2: an existing metric-naming namespace was itself fictional

The EXISTING `agent` namespace (shipped since `SPEC-OP-006`) had
`allowed_labels` (`agent_role`, `model`, `step_kind`) and `example_metrics`
that were never emitted anywhere in this codebase — a project-wide grep for
all 4 metric names returned zero matches. Corrected to the real shape:
`agent_runtime_workflow_started_total` and 15 others; labels
`workflow_type`, `checkpoint_inconsistent`, `event_type`, `outcome`.
`agent_role` IS a real, bounded domain concept but is never actually
attached as a metric label anywhere in `RuntimeTelemetry` — deliberately
left out of the corrected `allowed_labels` rather than contracting a label
nothing emits yet.

## 4. Real finding #3: the contract had never tested a GAUGE metric before

`naming.allowed_suffixes` had never included anything a real gauge would
satisfy — no fixture had ever used `type: gauge` before this spec. Both
`agent_runtime_outbox_pending` and `memory_outbox_backlog` are real,
already-shipped observable gauges. Added exactly `_pending`/`_backlog` and
the first-ever conformant GAUGE fixture.

## 5. Real finding #4: a millisecond-unit violation already shipped in domain 04's code

Three real histograms — `memory_search_latency_ms`,
`memory_graph_expansion_latency_ms`, `knowledge_document_ingestion_latency_ms`
— use a millisecond unit, violating `units.forbidden_substrings`'s `_ms`
entry. Deliberately not fixture-tested as conformant (would legitimately
fail this platform's own units check); documented plainly rather than
silently ignored. A real fix is a domain-04 follow-up (rename to
`_seconds`, values ÷ 1000) — outside this domain's charter to do
unilaterally.

## 6. Real docker-compose verification (2026-09-01, second build)

Pushed real OTLP metrics, each with one forbidden label riding along:
`agent_runtime_task_lease_expired_total{agent_task_id="SHOULD-BE-
STRIPPED"}=6`; `knowledge_embedding_failure_total{document_id="SHOULD-BE-
STRIPPED"}=7`. Confirmed exact raw counts (6, 7) reached Prometheus while
neither forbidden label did; both new recording rules query-valid; both new
alerts (`AgentRuntimeTaskLeaseExpiredHigh`, `MemoryEmbeddingProviderFailing`)
loaded. Every `SPEC-OP-002~029` assertion in the same run stayed green.

## 7. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| 3 real histograms use a millisecond unit, violating this platform's own units policy (§5) | Medium (a real, already-shipped defect, not this spec's to fix) | documented plainly; needs a domain-04 follow-up |
| Only 2 of the newly-contracted metrics have a dedicated alert | Low | additive follow-up |

## 8. Sign-off

Two more real, already-shipped business-metric surfaces are now contracted,
recorded, dashboarded, and alerted. Corrected a real pre-existing defect in
the contract itself, closed a gauge-suffix gap, documented (not papered
over) a real unit violation in another domain's code. Rebuilt faithfully
after data loss, re-verified live.
