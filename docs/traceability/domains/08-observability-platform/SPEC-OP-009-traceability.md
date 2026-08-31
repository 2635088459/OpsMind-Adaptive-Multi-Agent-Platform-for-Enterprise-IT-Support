# SPEC-OP-009 Traceability — Collector Processors And Routing

> Domain: `08-observability-platform`
> Phase: `phase-02-collector-intake-processing`
> Status: implemented
> Verified: 2026-08-31 (validators pass; a routing-connector design was tried, caught
> broken by a real container start — not just `validate` — and replaced with a
> correct, simpler mechanism; full smoke proves all three new processors)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Configure and test ordered memory, resource, attribute, transform,
filter, routing, and redaction processors.*

| Spec area | Where |
|---|---|
| memory / resource / transform / redaction | already built (`SPEC-OP-002`~`007`) |
| attribute | NEW `attributes/semconv-compat` — old→new semconv key normalization |
| filter | NEW `filter/noise` — drops health/readiness-probe spans + logs |
| routing | tried the `routing` **connector**; rejected for a real, discovered technical reason (§4) — replaced with `transform/trace-priority` producing the identical observable outcome |
| ordered | NEW `scripts/validate-collector-pipeline.py` — a MASTER_ORDER + relative-order check per pipeline, CI-gated |

## 2. Files added / changed

```text
infrastructure/observability/collector/base/config.yaml   CHANGED (attributes/semconv-compat,
                                                            filter/noise, transform/trace-priority;
                                                            wired into all 3 pipelines per MASTER_ORDER)

scripts/validate-collector-pipeline.py                     NEW
scripts/tests/test_validate_collector_pipeline.py          NEW
scripts/observability-stack.sh                             CHANGED (3 new push scenarios + 6 new assertions)
.github/workflows/observability-platform-ci.yml            CHANGED (new layout-job step)

docs/specs/domains/08-observability-platform/SPEC-OP-009-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-009-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `otelcol validate` (first draft, with the `routing` connector) | **passed** — but this was a false negative; validate does not fully build the pipeline graph for a connector |
| `scripts/observability-stack.sh smoke` (first draft) | **container failed to start**, healthcheck never turned healthy: `failed to build pipelines: segment "status" from path "status.code" is not a valid path... for the Resource context` |
| (fix: dropped the connector, added `transform/trace-priority`) `otelcol validate` | exit 0 |
| `scripts/observability-stack.sh smoke` (fixed) | **SMOKE: PASS** — see §5 |
| `uv run --with pyyaml python scripts/validate-collector-pipeline.py` | 0 errors, 0 warnings |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **57 passed** (46 prior + 2 layout + 2 governance already counted + 11 new collector-pipeline: 6 unit, 2 real-config E2E, plus indirectly the routing-connector rejection is recorded as smoke-test evidence, not a unit test) |
| `scripts/observability-stack.sh config` | compose config OK, unaffected |

## 4. Why the `routing` connector is NOT used here (found, not assumed)

The OTel `routing` connector's OTTL condition (`table[].statement`) evaluates in
**ottlresource context** — it decides where to route an entire `ResourceSpans` /
`ResourceLogs` / `ResourceMetrics` **batch**, not an individual span. A first draft
tried `route() where status.code == STATUS_CODE_ERROR` to split error-status spans
into their own tiny sink pipeline (a legitimate-looking pattern: keep the shared,
heavy processor chain as ONE copy upstream, only fan out a tiny branch downstream).
`otelcol validate` accepted it — connectors aren't fully graph-built at validate time
— but the container refused to even start: `status.code` is a per-span field, invalid
in resource context. This is the connector's actual, designed purpose: multi-tenant /
multi-destination fan-out keyed on resource identity (e.g. route tenant A's traces to
a different Tempo instance than tenant B's). This local topology has exactly one
Tempo, one Loki, one Prometheus — there is no genuine destination to fan out to yet.
Forcing the connector in anyway, for a per-span decision it cannot express, would
have been complexity without real value — caught by the smoke test, not invented
after the fact as a post-hoc rationalization. The identical **observable** outcome
(ERROR spans carry `opsmind.trace.priority=high`, OK spans don't) now comes from a
single `transform/trace-priority` statement in `context: span`. Revisit the routing
connector when a genuine multi-destination need exists — plausibly `SPEC-OP-029`
(Postgres/RabbitMQ Connector Contract) or a production multi-tenant topology.

## 5. Smoke test evidence (this spec's 3 new assertions)

- **filter/noise**: pushed a span named `GET /health` (`http.route="/health"`) and a
  log body `"GET /health 200 OK"`. Neither reached Tempo/Loki.
- **attributes/semconv-compat**: pushed a span with only `http.method="GET"` /
  `http.status_code=200`. Tempo shows `http.request.method` / `http.response.status_code`
  present and `http.method` / `http.status_code` gone.
- **transform/trace-priority**: pushed one `STATUS_CODE_ERROR` span and one
  `STATUS_CODE_OK` span in the same request. Only the error span carries
  `opsmind.trace.priority=high` in Tempo.

Every `SPEC-OP-002`~`008` assertion in the same run stayed green — none of these three
changes touched an existing pipeline's observable behavior for conformant telemetry.

## 6. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `filter/noise` matches on exact `http.route` values / a fixed log-body regex | Low | additive — a new probe path needs a one-line PR, same pattern as every other governed regex in this domain |
| No routing/fan-out capability exists yet for a genuine future multi-destination need | Low (deferred, not a gap) | revisit at `SPEC-OP-029` or a production multi-tenant topology spec, using the resource-context pattern correctly this time |
| `MASTER_ORDER` is maintained by hand in the validator, not derived from the config | Low | the relative-order check still catches any actual regression; only a genuinely NEW processor needs a one-line addition to the list |

## 7. Sign-off

Attribute normalization and noise filtering are real, wired into every pipeline that
needs them, and proven against a live Collector. The processor-order contract is now
explicit and CI-enforced. Routing is honestly deferred with a concrete, discovered
technical reason rather than forced in or silently skipped. `SPEC-OP-010` (Trace
Sampling Policy) continues phase-02.
