# SPEC-OP-025 Traceability — Identity And Ticket Observability Contract

> Domain: `08-observability-platform`
> Phase: `phase-06-cross-domain-contracts` (opens this phase)
> Status: implemented
> Verified: 2026-09-01 (rebuilt after a mid-session data-loss incident — see §5)
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Contract identity security and ticket business signals
with bounded labels and trace fields."*

| Requirement | Where |
|---|---|
| Identity security signals, bounded labels | `metric-naming.yaml` `identity` namespace (6 real metrics from `MicrometerIdentityMetrics`, SPEC-UA-030/032) |
| Ticket business signals, bounded labels | `metric-naming.yaml` `opsmind` namespace (183 real metrics from `TicketTelemetry`) |
| Query/dashboard artifact | `dashboards/identity-ticket-business-signals.json` |
| Rule/runbook artifact | `rules/{recording,alerting}/identity-ticket-business.yml` + `runbooks/IdentityTicketBusinessSignals.md` |
| Trace fields | Already covered generically by `SPEC-OP-004/005`'s platform-wide trace-context propagation and `SPEC-OP-017`'s per-domain log/trace drilldown — no new work needed |

## 2. Real finding: a multi-word metric-name prefix breaks the namespace convention

`ticket-workflow-service`'s real `TicketTelemetry.java` emits 183 distinct,
fully-wired business metrics — but every one is prefixed with the literal
token `opsmind_`, unlike every other domain's clean single-word self-
namespacing (`identity_`, `agent_`, `evaluation_`, `tool_`, `approval_`,
`memory_`). `validate-signal-contracts.py`'s namespace check extracts a
metric's namespace as its first underscore-delimited segment
(`name.split("_", 1)[0]`); for all 183 of ticket-workflow-service's metrics
that segment is `opsmind`, never `ticket`.

**Decision:** name the namespace `opsmind`, matching the real, already-
shipped convention exactly. The alternative (renaming 183 already-tested
metrics in another domain's already-shipped service) would violate
`12-observability`'s own cross-domain boundary rule.

## 3. Real finding: the validator's forbidden-label cross-check has an incidental blind spot

Adding the new namespaces' `forbidden_labels` surfaced 4 real errors from the
Collector regex cross-check, but notably NOT `token` (also newly forbidden)
— not because it was covered, but because the validator's cross-check
merely tests whether the forbidden-label string appears anywhere in the
whole collector config file as plain text, and `token` incidentally already
appears there (the `bearertokenauth` extension name) — a false negative, not
real coverage. Added `token` to the real OTTL regex anyway.

## 4. Real finding: a labeling mistake caught before it ever reached a live query

Grepped every specific metric used in the dashboard against
`TicketTelemetry.java`'s own `.tag(...)` calls before writing any PromQL.
This caught the dashboard draft's "Support-queue authorization decisions"
panel grouping by `outcome` — but
`opsmind_support_queue_authorization_decisions_total` only ever carries
`decision_code`. Fixed before it was ever queried live.

## 5. Recovery note: rebuilt after mid-session data loss

This spec (along with SPEC-OP-024, 026, 027, 028, and most of SPEC-OP-029)
was originally built, verified, and documented in full — then silently lost
when the repository's checked-out branch changed mid-session (from `main`
to an unrelated `appmod/java-upgrade-*` branch neither this session nor the
user initiated), discarding all uncommitted work back to an earlier commit.
The user asked for the lost work to be redone from memory rather than
re-derived from scratch. All content in this document reflects the
faithfully-reconstructed original build, re-verified live a second time
(see §6) — not a summary of a run that no longer has direct evidence.
**Process fix applied going forward:** commit after completing each spec,
rather than accumulating multiple specs' uncommitted work.

## 6. Real docker-compose verification (2026-09-01, second build)

Pushed real OTLP metrics via the smoke-test script, each also carrying one
forbidden label riding along:

- `identity_authorization_decision_total{effect="DENY"}=8`,
  `{effect="ALLOW"}=2`; `identity_step_up_total{outcome="VERIFIED",
  subject="SHOULD-BE-STRIPPED"}=1`.
- `opsmind_ticket_event_dlq_total{event_type="TicketStatusChanged",
  ticket_id="SHOULD-BE-STRIPPED"}=3`; `opsmind_ticket_created_total
  {application_code="IT_SUPPORT", source="USER_PORTAL"}=4`.

Confirmed:

- `identity_authorization_decision_total{effect="DENY"}` raw count in
  Prometheus is exactly `8`; `opsmind_ticket_event_dlq_total` raw count is
  exactly `3`.
- Neither `subject` nor `ticket_id` reached Prometheus on any series.
- `identity:authorization_denial:ratio5m` and `opsmind:ticket_event_dlq:rate5m`
  both query-valid.
- `HighIdentityAuthorizationDenialRate` and `TicketEventDeadLettered` both
  loaded via Prometheus's own `/api/v1/rules`.
- Every `SPEC-OP-002~029` assertion in the same run stayed green, including
  `SPEC-OP-029`/`SPEC-OP-030`'s own real infra-exporter and auth/RBAC
  verification (both built after this spec, in the same domain).

## 7. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Only 2 of the ~189 total contracted metrics have a dedicated alert | Low | additive follow-up if a real operational need surfaces |
| The `opsmind` namespace name reads unusually next to single-word namespaces | Low | intentional and documented — it must literally equal the metric name's first underscore segment |

## 8. Sign-off

Two real, already-shipped, previously-uncontracted business-metric surfaces
are now bounded-label-contracted, recorded, dashboarded, and alerted,
proven against real pushed data including real forbidden-label stripping.
Rebuilt faithfully after a mid-session data-loss incident, re-verified live
a second time. Opens `phase-06-cross-domain-contracts`.
