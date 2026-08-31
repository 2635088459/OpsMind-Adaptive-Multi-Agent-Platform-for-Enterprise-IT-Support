# Metric Naming and Cardinality Contract

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-006
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: standard
> runbook: runbooks/MetricCardinalityBudget.md
> rollback: git revert <sha>; redeploy otel-collector + prometheus
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-006-traceability.md

Metrics are the sensitive resource: an unbounded label blows up Prometheus and every
dashboard built on it. This contract fixes names, units, types, buckets, label
allow-lists, and per-service series budgets.

Machine-readable form: [`metric-naming.yaml`](metric-naming.yaml)
(schema [`../schemas/metric-naming.schema.json`](../schemas/metric-naming.schema.json)).
Fixtures: [`fixtures/metric-naming/`](fixtures/metric-naming/).
Namespace budgets come from
[`../governance/telemetry-governance.yaml`](../governance/telemetry-governance.yaml)
`cardinality_budgets` — this file must stay in sync (`validate-signal-contracts.py`).

## 1. Names

- `snake_case`, ASCII, `^[a-z][a-z0-9_]*[a-z0-9]$`.
- First token is the **namespace**: `http`, `amqp`, `db`, `runtime`, `agent`,
  `evaluation`, `slo`, `otelcol`. New namespaces need a governance entry.
- OpenTelemetry semantic conventions win where they exist (`http.server.request.duration`
  → exported as `http_server_request_duration_seconds`).
- No environment / service / instance in the name — those are resource attributes
  (SPEC-OP-004).

## 2. Units

- SI base units only, as a **name suffix**: `_seconds`, `_bytes`, `_ratio` (0–1),
  `_total` (count), `_usd`, `_tokens`, `_info` (gauge==1).
- **Forbidden**: `_ms`, `_us`, `_ns`, `_millis`, `_kb`, `_mb`, `_gb`, `_percent`,
  `_count` on a non-histogram, `_bytes_total`, a plural bare noun with no unit.
- Durations are always `_seconds` (float). Percentages are `_ratio` in [0,1].

## 3. Types → suffix

| OTel instrument | Prometheus type | Name suffix |
|---|---|---|
| Counter | counter | `_total` |
| UpDownCounter | gauge | none (or `_bytes` / unit) |
| Gauge / async gauge | gauge | none (or unit) |
| Histogram | histogram | none on the base; `_bucket` / `_sum` / `_count` emitted |

## 4. Histogram buckets

Named bucket sets in `metric-naming.yaml` `bucket_sets`:

| Set | Boundaries | For |
|---|---|---|
| `latency_seconds` | 0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10 | HTTP / RPC / DB / tool latency |
| `payload_bytes` | 64 256 1024 4096 16384 65536 262144 1048576 4194304 | request/response/message size |
| `llm_tokens` | 16 64 256 1024 4096 16384 65536 | prompt / completion token counts |
| `cost_usd` | 0.0005 0.005 0.05 0.5 1 5 | per-call model cost |

Native histograms (Prometheus 3.x) are allowed; a producer that emits a native
histogram MUST also be safe if scraped as classic (the sets above are the classic
fallback).

## 5. Namespaces — label allow-lists

Only the listed label keys may appear on a metric in that namespace. Everything else
is dropped at the Collector (`transform/metric-cardinality`) and flagged by
`ForbiddenMetricLabel`.

| Namespace | Allowed labels | Never a label |
|---|---|---|
| `http` | `http_request_method`, `http_response_status_code`, `http_route` (templated), `server_address`, `network_protocol_version`, `outcome` | raw path, `user_id`, `ticket_id`, `workflow_id`, `request_id`, `trace_id`, `session_id`, `email` |
| `amqp` | `messaging_system`, `messaging_destination_name`, `messaging_operation`, `outcome` | `message_id`, `correlation_id`, `ticket_id`, `workflow_id` |
| `db` | `db_system`, `db_operation`, `db_collection_name`, `outcome` | raw SQL, `db_statement`, row ids |
| `runtime` | `runtime`, `pool` | pid, thread name |
| `agent` | `agent_role`, `model`, `outcome`, `step_kind` | `ticket_id`, `workflow_id`, `run_id`, `user_id`, `prompt_hash` |
| `evaluation` | `grader`, `dataset`, `outcome`, `severity` | `case_id`, `run_id`, `candidate_id`, `ticket_id` |
| `slo` | `slo`, `objective`, `window` | anything per-request |
| `otelcol` | (collector-internal labels as shipped) | — |

Bounded value guidance: `http_route` ≤ 50 distinct per service; `model` ≤ 20;
`grader` ≤ 30; `messaging_destination_name` ≤ 40.

## 6. Per-service series budgets

`metric-naming.yaml` `service_series_budgets` maps `service.namespace` → max active
series per environment. The sum must stay under
`governance.cardinality_budgets.global.max_series_total` (local 250 000). Exceeding a
budget is a `MetricSeriesBudgetExceeded` alert, not a drop.

## 7. Exemplars

Histograms SHOULD attach an exemplar carrying `trace_id` (and only `trace_id`) so a
latency spike links straight to a trace. Exemplars are not labels and do not count
against cardinality.

## 8. Enforcement

| Layer | Control |
|---|---|
| Producer | follow this contract + OTel semconv; a metrics SDK view drops disallowed attributes |
| Collector | `transform/metric-cardinality` deletes forbidden id/hash/path label keys (regex derived from the union of every namespace's `forbidden_labels` in governance) from metric datapoint attributes before export |
| CI | `scripts/validate-signal-contracts.py` — `.yaml` shape, namespace sync with governance, fixture metrics pass/fail the name+unit+type+label rules, and the Collector regex covers every governance forbidden label |
| Runtime | recording rule `job:series:count`; alerts `MetricSeriesBudgetExceeded`, `HighCardinalityJob`, `ForbiddenMetricLabel` (`rules/alerting/cardinality.yml`) |

## 9. Schema evolution

New metric / new allowed label / new bucket set → additive, PR + `platform-observability`
+ the namespace's semantic owner. Renaming a metric, changing a unit, removing an
allowed label, or tightening a budget → breaking per `governance schema_review`; bump
this file's `version`.
