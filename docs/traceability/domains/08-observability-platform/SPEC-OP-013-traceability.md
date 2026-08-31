# SPEC-OP-013 Traceability — Loki Log Backend

> Domain: `08-observability-platform`
> Phase: `phase-03-telemetry-backends-retention`
> Status: implemented
> Verified: 2026-08-31 (`-verify-config` passes; the ruler's LogQL rule was proven
> against a real ingested log — inactive → pending with the correct matched value,
> not just loaded)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deploy Loki write/read, schema, index/chunks, tenants, limits,
retention, LogQL, and metadata.*

| Spec area | Where |
|---|---|
| write / read / schema / index/chunks / base retention | already existed (`SPEC-OP-002`) |
| LogQL (ruler) | NEW — the ruler was present but unwired since `SPEC-OP-002`; now loads and evaluates a real LogQL alert |
| limits | NEW — query-safety limits (`max_query_series`, `max_entries_limit_per_query`, `max_query_parallelism`, `per_stream_rate_limit`) |
| tenants | deliberate non-decision — single-tenant stays; real multi-tenancy is `SPEC-OP-031` |
| retention (per-severity) | deliberate non-decision — needs severity promoted to an indexed label first; deferred to `SPEC-OP-015` |
| write/read path separation | deliberate non-decision — single-binary stays; no current scale need |
| metadata | already covered (`allow_structured_metadata: true`, `SPEC-OP-002`) — this spec's ruler rule is itself a consumer of it |

## 2. Files added / changed

```text
infrastructure/observability/
  loki/base/loki.yml                       CHANGED (ruler.storage.local.directory,
                                            alertmanager_url, enable_alertmanager_v2,
                                            query-safety limits, scope-decision comments)
  loki/rules/fake/log-quality.yaml          NEW

infrastructure/docker-compose/observability-stack.yml   CHANGED (loki/rules bind mount)
scripts/observability-stack.sh                          CHANGED (1 new smoke assertion)

docs/specs/domains/08-observability-platform/SPEC-OP-013-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-013-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `loki -verify-config` | `msg="config is valid"` |
| `docker compose up` | Loki healthy; `GET /loki/api/v1/rules` shows `log-quality.yaml` / `HighLogSchemaViolationRate` loaded from the bind mount |
| `GET /loki/api/v1/labels` (before writing any retention_stream rule) | `["deployment_environment","service_name"]` — confirmed severity is NOT an indexed label today, the empirical reason per-severity retention is deferred, not assumed |
| Pushed a log with **no** `trace_id`/`correlation_id` attribute; waited ~75s | `GET /prometheus/api/v1/rules`: `state` went `inactive` → `pending`, `value: "1e+00"` |
| `docker logs opsmind-loki` | `query_referenced_structured_metadata=true`, `org_id=fake`, the exact configured LogQL expression — real execution, not a cached/static response |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — new assertion: `/prometheus/api/v1/rules` contains `HighLogSchemaViolationRate`. Every `SPEC-OP-002`~`012` assertion in the same run stayed green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Three deliberate, evidence-backed non-decisions

1. **Write/read path separation** — kept single-binary. No current volume justifies
   the operational cost of a split; the config's sections (schema/limits/compactor/
   ruler) are already independent enough that splitting later is additive, not a
   rewrite.
2. **Real multi-tenancy** — `auth_enabled: false` stays. Already named as
   `SPEC-OP-031`'s job (the trace-propagation contract's `tenant.id` baggage key
   already anticipates this).
3. **Per-severity retention (`retention_stream`)** — reasoned about first, then
   **verified empirically** (`/loki/api/v1/labels`) that severity is not an indexed
   label under the current OTLP-to-label mapping. Promoting it would be a real,
   reviewable cardinality decision in its own right, not a side effect of wiring a
   ruler. Deferred to `SPEC-OP-015`, the spec that explicitly formalizes
   retention-by-signal-class across Prometheus, Loki, and Tempo together.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `HighLogSchemaViolationRate` never observed in `firing` state (only `pending`, since `for: 10m` wasn't waited out in full) | Low | `inactive→pending` with the correct matched value is the load-bearing proof; `for`→`firing` is standard, unmodified Prometheus/Loki-ruler timer mechanics |
| Query-safety limit values are laptop-scale estimates | Low | `SPEC-OP-015`/production topology sets real numbers against real traffic |
| Ruler's own reliability (single ring member, `inmemory` kvstore) isn't itself alerted on | Low | `SPEC-OP-033` (Observability Self-Monitoring) is the natural home for meta-monitoring the ruler itself |

## 6. Sign-off

The Loki ruler is no longer inert config — it loads a real LogQL rule from a
GitOps-reviewed bind mount and evaluates it against real ingested logs, proven by
watching a real alert state transition, not just a config parse. Query-safety limits
bound LogQL cost. Three real scope boundaries (tenancy, path separation, per-severity
retention) are recorded with the evidence behind each decision. `SPEC-OP-014` (Tempo
Trace Backend) continues phase-03.
