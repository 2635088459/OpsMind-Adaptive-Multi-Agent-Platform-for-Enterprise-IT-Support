# ToolPolicyBusinessSignals

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-027
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-027-traceability.md

Covers both alerts SPEC-OP-027 adds: `ToolConnectorErrorRateHigh` (`tool`
namespace, tool-integration-gateway) and `GovernancePolicyDegradedSustained`
(`governance` namespace, policy-approval-governance-service) — one file per
this spec's own incident class, same grouping convention as
`RuntimeMemoryBusinessSignals.md`.

## Impact

- `ToolConnectorErrorRateHigh`: real tool executions requested by agents or
  operators are failing — downstream work that depends on the tool result
  (a ticket update, a remediation action) does not complete.
- `GovernancePolicyDegradedSustained`: policy/approval decisions are being
  made under SPEC-PG-032's reduced-confidence Degraded Policy Mode rather
  than the normal evaluation path — a real risk to the correctness of
  authorization/approval outcomes across every domain this service gates.

## Detection

- Firing expressions:
  - `tool:connector_error:rate5m > 0` for 10m
    (`sum(rate(tool_connector_error_total[5m]))`)
  - `governance:policy_degraded:rate5m > 0` for 5m
    (`sum(rate(governance_policy_degraded_total[5m]))`)
- Dashboard: `dashboards/tool-policy-business-signals.json` ("Tool & Policy
  Business Signals")
- Correlation entry point: filter the dashboard's log panel by
  `service_namespace` (`tool-integration` or `policy-approval-governance`)
  and the relevant `trace_id`.

## Triage

1. Check which alert fired — they have unrelated root causes.
2. For `ToolConnectorErrorRateHigh`: break down `tool_connector_error_total`
   by `connector` and `error_code` to see whether one connector is
   responsible or the failure is broad-based; compare against
   `tool_connector_timeout_total` to distinguish a hard error from a timeout.
3. For `GovernancePolicyDegradedSustained`: break down
   `governance_policy_degraded_total` by `effect` (ALLOW/DENY/REQUIRE_STEP_UP)
   to see what the degraded path is actually deciding, then check
   `policy_evaluation_failure_total` for the same window to see whether a
   raw evaluator failure is driving the fallback into degraded mode.

## Mitigation

- `ToolConnectorErrorRateHigh`: tool-integration-gateway's own connector-
  level degraded-fallback and retry/backoff (already built) handle a single
  unavailable connector automatically; if the affected connector needs to be
  disabled or a credential rotated, that is domain 05's own operational call,
  not something this runbook instructs from the observability side.
- `GovernancePolicyDegradedSustained`: no direct mitigation from this side —
  restoring the normal evaluation path is domain 06's own incident, not a
  business-state mutation this runbook performs
  ([forbidden-business-writes §4](../docs/forbidden-business-writes.md)).

## Resolution

- `ToolConnectorErrorRateHigh`: durable fix is domain 05's — a restored
  connector, corrected credential, or fixed integration. Confirm resolution
  by watching `tool:connector_error:rate5m` return to `0`.
- `GovernancePolicyDegradedSustained`: durable fix is domain 06's — a
  restored policy evaluator. Confirm resolution by watching
  `governance:policy_degraded:rate5m` return to `0`.

## Rollback

Exact revert: `git revert <sha>` on this runbook / the two rule files
(`rules/recording/tool-policy-business.yml`,
`rules/alerting/tool-policy-business.yml`); `promtool check rules`; recreate
Prometheus. Reverting this spec only removes the ALERT — it does not touch
either producing domain's own metrics code.

## Escalation

- `ToolConnectorErrorRateHigh` (`warning`): opens a ticket against
  tool-integration-gateway's on-call (`service_namespace: tool-integration`)
  — domain 08 defines and detects the signal, it does not remediate it
  (ADR-0004).
- `GovernancePolicyDegradedSustained` (`critical`, paging): pages
  policy-approval-governance-service's on-call
  (`service_namespace: policy-approval-governance`) directly — sustained
  degraded-mode governance decisions are urgent enough to page, not queue as
  a ticket.

## Post-incident

Link the traceability entry
(`docs/traceability/domains/08-observability-platform/SPEC-OP-027-traceability.md`).
Residual risk: this spec only contracts and alerts on the subset of each
service's already-emitted metrics most directly tied to business-visible
failure (`tool_connector_error_total`, `governance_policy_degraded_total`) —
the other 11 already-real `tool_*` and 8 other
`policy_*`/`governance_*`/`approval_*` metrics are now contracted (bounded
labels enforced) but have no dedicated alert yet; a follow-up spec could add
more if a real operational need surfaces.
