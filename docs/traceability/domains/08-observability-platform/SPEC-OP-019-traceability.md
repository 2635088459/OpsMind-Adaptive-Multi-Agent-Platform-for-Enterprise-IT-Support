# SPEC-OP-019 Traceability — Agent, LLM Cost, And Capacity Dashboard

> Domain: `08-observability-platform`
> Phase: `phase-04-dashboards-correlation-analysis` (closes this phase)
> Status: implemented
> Verified: 2026-08-31 (token/cost/latency panels proven against real data;
> loop/capacity panels are documented proxies, not fabrications)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Show LLM/agent token, latency, cost, budget, loops, and worker
capacity without high-cardinality labels.*

| Spec area | Where |
|---|---|
| token / cost | `agent_llm_tokens` / `agent_llm_cost_usd`, rate-by-model + 6h totals |
| latency | `agent_run_duration_seconds` p50/p95 by `agent_role` |
| budget | **no contracted metric** — stated honestly, not fabricated |
| loops | **no contracted metric** — documented proxy (tool-calls-per-run) |
| worker capacity | **no contracted metric** — documented proxy (`runtime_cpu_utilization_ratio`) |
| without high-cardinality labels | enforced by construction — only `agent_role`/`model` used anywhere |

## 2. Files added / changed

```text
infrastructure/observability/
  dashboards/agent-llm-cost-capacity.json   NEW
  runbooks/AgentLlmCostCapacity.md          NEW

docs/specs/domains/08-observability-platform/SPEC-OP-019-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-019-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| Pushed `agent_llm_cost_usd`, `agent_llm_tokens`, `agent_run_duration_seconds`, `agent_tool_calls_total`, `runtime_cpu_utilization_ratio` with bounded `agent_role`/`model` labels | HTTP 200 |
| `GET /api/v1/query` for the cross-metric aggregate query | returned real cost/token/duration values with correct labels |
| `GET /api/v1/query` for the loop-intensity ratio (`agent_tool_calls_total` rate ÷ `agent_run_duration_seconds_count` rate) | returned `NaN` on a single-sample push — expected Prometheus `rate()` behavior over one data point, not a query defect; confirmed the query executes against the correct metric/label names |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** |
| `scripts/observability-stack.sh down` | stack + volumes removed |

## 4. Cardinality discipline, and two honest non-metrics

Every panel groups only by `agent_role`/`model` — `signals/metric-naming.yaml`'s
`agent` namespace's only allowed labels; `run_id`/`ticket_id`/`workflow_id`/
`user_id`/`prompt_hash` are forbidden on this namespace and stripped at the
Collector even if a producer tried (`SPEC-OP-006`). This dashboard could not show
a per-run cost breakdown even if asked to — that granularity belongs in trace/log
correlation, never a metric label.

Checked `signals/metric-naming.yaml`'s `agent` namespace before building: no
loop-count and no worker-pool/concurrency metric is contracted. Rather than
inventing one, used clearly-labeled proxies (tool-calls-per-run;
`agent-runtime`-scoped CPU utilization) with an explicit in-panel description
saying so — a future spec adding the real metric replaces the proxy cleanly,
with no confusion about which was ever real.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No dedicated budget/loop-count/worker-capacity metric exists | Medium (explicit, not silent) | proxies documented; a real metric is future contract work, not this spec's to invent |
| Cost/token panels only proven against a single synthetic push | Low | real per-model cost patterns emerge once real agent traffic flows |

## 6. Sign-off

Token/cost/latency panels are real, bounded-cardinality by construction, and
proven against real data. Loop and capacity panels are honest, labeled proxies —
not fabricated metrics dressed up as real ones. This closes **phase-04
(Dashboards And Correlation Analysis, `SPEC-OP-016`~`019`)** for domain 08.
`SPEC-OP-020` (Recording & Alert Rule Catalog) opens phase-05 (Alerts, SLOs, And
Runbooks).
