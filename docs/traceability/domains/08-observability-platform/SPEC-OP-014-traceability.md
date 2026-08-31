# SPEC-OP-014 Traceability — Tempo Trace Backend

> Domain: `08-observability-platform`
> Phase: `phase-03-telemetry-backends-retention`
> Status: implemented
> Verified: 2026-08-31 (the entire pipeline/metrics_generator/TraceQL/exemplar chain
> proven end to end against a live stack — this spec's real deliverable, since the
> config already existed and had never been exercised)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deploy Tempo pipeline, block storage, TraceQL, exemplars,
compaction, and metrics generator.*

| Spec area | Where |
|---|---|
| pipeline / block storage / compaction | already existed and configured (`SPEC-OP-002`) |
| metrics generator (span-metrics + service-graphs) | already configured; **proven working live** for the first time this spec |
| TraceQL | native Tempo capability; **proven** via `/api/search` |
| exemplars | `send_exemplars: true` already set; **proven** via Prometheus's `query_exemplars` API returning the exact trace ID |
| self-monitoring | NEW — `rules/{recording,alerting}/tempo-health.yml` against real, verified Tempo self-metrics |

## 2. Files added / changed

```text
infrastructure/observability/
  tempo/base/tempo.yml                    CHANGED (header comment only — records this
                                           spec's live verification, no config change)
  rules/recording/tempo-health.yml        NEW
  rules/alerting/tempo-health.yml         NEW
  runbooks/TempoIngestHealth.md           NEW

.github/workflows/observability-platform-ci.yml   CHANGED (2 new rule files in promtool step)
scripts/observability-stack.sh                    CHANGED (1 new push + 2 new polling assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-014-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-014-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| First span push (shell string interpolation, `ff'"$i"'ffff...`) | produced **33-character** trace IDs — `GET /api/traces/<id>` returned 404. Caught immediately as a test-harness bug (verified the malformed length with `wc -c`), not attributed to Tempo. |
| Redone in Python with `assert len(tid)==32 and len(sid)==16` | 3 pushes, HTTP 200 each |
| `GET /api/traces/<id>` | 200, real span data returned |
| `GET /api/search?q={resource.service.name="metrics-gen-probe"}` (TraceQL) | found the trace |
| First Prometheus check at ~20s post-push | **empty result** — too early, not a failure; corrected by waiting longer instead of concluding metrics_generator was broken |
| Prometheus check after ~45s | `traces_spanmetrics_calls_total{service="metrics-gen-probe",...} = 3`; `traces_service_graph_request_total`, `traces_spanmetrics_latency_bucket` also present with full expected label sets |
| `GET /api/v1/query_exemplars?query=traces_spanmetrics_latency_bucket...` | exemplar with `"traceID": "ff000000000000000000000000000002"` — the exact ID pushed |
| `curl :9090/metrics` equivalent (label values API) for `tempo_*` names | confirmed real: `tempo_discarded_spans_total`, `tempo_ingester_failed_flushes_total`, `tempo_metrics_generator_spans_discarded_total`, `tempo_metrics_generator_registry_collections_failed_total` |
| `promtool check rules` (both new files) | SUCCESS — 3 recording + 3 alerting |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — new assertions (polling up to 60s): `traces_spanmetrics_calls_total` reached Prometheus; the exemplar links to the exact pushed trace ID. Every `SPEC-OP-002`~`013` assertion in the same run stayed green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Two real mistakes caught mid-verification (both self-corrected)

1. **Malformed trace/span IDs from shell interpolation** — `ff'"$i"'ffff...` produced
   a 33-character trace ID (invalid; OTel trace IDs are exactly 32 hex chars).
   Confirmed with `wc -c` and rewritten in Python with an explicit length assertion
   rather than another fragile shell string trick.
2. **Checking Prometheus too early** — `traces_spanmetrics_calls_total` was empty at
   ~20s post-push. Rather than concluding metrics_generator was broken, waited
   longer (its own collection interval + remote_write cycle) and confirmed the
   metric appears correctly — the smoke-test polling loop (up to 60s) encodes this
   lesson so a future run doesn't need to rediscover it.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Metrics-generator's own collection interval adds real latency (~30-45s) before span-metrics are queryable | Low | inherent to the architecture; the smoke test now polls rather than asserting immediately |
| `tempo_metrics_generator_registry_collections_failed_total` has no dedicated alert yet | Low | `tempo:metrics_generator_spans_discarded:rate5m` is the closest proxy; add a dedicated rule if this metric is ever seen non-zero in practice |
| Block retention (`TEMPO_BLOCK_RETENTION=24h` local) is a laptop-scale estimate | Low | `SPEC-OP-015` sets real production sizing |

## 6. Sign-off

Every piece of Tempo's pipeline named in this spec's objective — block storage,
TraceQL, metrics_generator, and exemplars — is proven working end to end against a
live stack, not merely present in a config file. Two real verification mistakes
(malformed test IDs, checking too early) were caught and corrected in the process,
not glossed over. Self-monitoring closes the observability-of-Tempo gap.
`SPEC-OP-015` (Telemetry Retention, Compaction, And Storage) closes phase-03.
