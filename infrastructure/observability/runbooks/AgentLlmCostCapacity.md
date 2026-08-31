# AgentLlmCostCapacity

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-019
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: n/a (view only)
> runbook: self
> rollback: git revert <sha>; re-run grafana provisioning
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-019-traceability.md

Companion runbook for the **Agent / LLM Cost & Capacity** dashboard
(`dashboards/agent-llm-cost-capacity.json`).

## Cardinality discipline (the goal's own explicit requirement)

Every panel here groups only by `agent_role` and `model` — the two bounded
labels the `agent.*` namespace allows (`SPEC-OP-006`:
`signals/metric-naming.yaml` `agent` namespace `allowed_labels`). `run_id`,
`ticket_id`, `workflow_id`, `user_id`, and `prompt_hash` are explicitly
**forbidden** on this namespace and are stripped at the Collector
(`transform/metric-cardinality`) even if a producer tried to emit them — this
dashboard could not show a per-run or per-ticket cost breakdown even if asked to;
that granularity belongs in trace/log correlation (click through via the Golden
Path dashboard's trace drilldown), never a metric label.

## What each row shows

- **Token usage & cost**: 6h totals (stat panels) and rate-by-model
  (`agent_llm_cost_usd`, `agent_llm_tokens` — both histograms per
  `metric-naming.yaml`'s `cost_usd`/`llm_tokens` bucket sets).
- **Latency, loops, worker capacity**: agent run duration p50/p95;
  tool-calls-per-run as a "loop intensity" proxy (no dedicated loop-count metric
  is contracted yet — see the panel description); CPU utilization on the
  `agent-runtime` service namespace as a worker-capacity proxy (no dedicated
  worker-pool/concurrency metric is contracted yet either).

## Budget

No formal cost-budget metric is contracted yet. Compare the Cost (USD) stat panel
against your own known budget figure manually until a budget/threshold metric
exists; that is real future work, not something to fabricate here.

## Data provenance

Verified live against synthetic-but-contract-compliant `agent_llm_cost_usd`/
`agent_llm_tokens`/`agent_run_duration_seconds`/`agent_tool_calls_total`/
`runtime_cpu_utilization_ratio` pushed by `scripts/observability-stack.sh smoke`
— see the traceability doc.

## Rollback

`git revert` the dashboard/runbook change; re-run Grafana provisioning.
