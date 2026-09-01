# IdentityTicketBusinessSignals

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-025
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-025-traceability.md

Covers both alerts SPEC-OP-025 adds: `HighIdentityAuthorizationDenialRate`
(`identity` namespace, user-access-authentication-service) and
`TicketEventDeadLettered` (`opsmind` namespace, ticket-workflow-service) — one
file per this spec's own incident class, same grouping convention as
`SloBurnRateAlerts.md`.

## Impact

- `HighIdentityAuthorizationDenialRate`: users may be legitimately locked out of
  actions they should have access to (a real business impact), or the platform
  is under a credential-stuffing / scanning attempt (a security impact). The
  business request path itself is not down — authorization is working as
  designed, just denying more than usual.
- `TicketEventDeadLettered`: real ticket business events (creation, state
  transitions, approvals, tool-execution outcomes) are failing to process.
  Affected tickets will not progress until the dead-lettered events are
  triaged and replayed — a direct business impact on support-ticket SLAs.

## Detection

- Firing expressions:
  - `identity:authorization_denial:ratio5m > 0.5` for 10m
    (`sum(rate(identity_authorization_decision_total{effect="DENY"}[5m])) / sum(rate(identity_authorization_decision_total[5m]))`)
  - `opsmind:ticket_event_dlq:rate5m > 0` for 5m
    (`sum(rate(opsmind_ticket_event_dlq_total[5m]))`)
- Dashboard: `dashboards/identity-ticket-business-signals.json` ("Identity &
  Ticket Business Signals")
- Correlation entry point: filter the dashboard's log panel by
  `service_namespace` (`user-access-authentication` or `ticket-workflow`) and
  the relevant `trace_id` to see the exact request(s) behind the denial spike
  or the dead-lettered event.

## Triage

1. Check which alert fired — they have unrelated root causes.
2. For `HighIdentityAuthorizationDenialRate`: break down
   `identity_authorization_decision_total` by `effect` over the last hour. A
   step change coinciding with a deploy suggests a policy/role regression; a
   gradual, broad-based rise across many distinct source IPs/sessions (check
   correlated access logs, not a metric label — `session_id`/`user_id` are
   deliberately never metric labels) suggests a scanning/credential-stuffing
   pattern instead.
3. For `TicketEventDeadLettered`: check `opsmind_ticket_event_consumed_total`
   vs `opsmind_ticket_event_dlq_total` to see the DLQ rate as a fraction of
   total consumption, then inspect ticket-workflow-service's own DLQ (RabbitMQ
   dead-letter queue) for the actual failed message payloads and exception
   traces.

## Mitigation

- `HighIdentityAuthorizationDenialRate`: if a recent role/policy deploy is the
  cause, roll it back through user-access-authentication-service's own
  deployment/rollback path (domain 01 owns this — this runbook does not
  instruct a direct state mutation from the observability side). If it's a
  suspected attack, this is domain 01's own incident-response call (WAF/rate-
  limit/IP-block decisions are entirely outside this domain's ownership).
- `TicketEventDeadLettered`: do not manually purge the dead-letter queue.
  Ticket-workflow-service owns its own DLQ replay tooling; any replay or
  correction must go through that domain's own operational path — this
  runbook's job is detection and triage only, not intervention
  ([forbidden-business-writes §4](../docs/forbidden-business-writes.md)).

## Resolution

- `HighIdentityAuthorizationDenialRate`: durable fix is domain 01's — either a
  corrected policy/role deploy or a closed security gap. Confirm resolution by
  watching `identity:authorization_denial:ratio5m` return to its historical
  baseline.
- `TicketEventDeadLettered`: durable fix is domain 02's — a corrected consumer
  or a fixed upstream producer, followed by a real DLQ replay. Confirm
  resolution by watching `opsmind:ticket_event_dlq:rate5m` return to (and
  stay at) `0`.

## Rollback

Exact revert: `git revert <sha>` on this runbook / the two rule files
(`rules/recording/identity-ticket-business.yml`,
`rules/alerting/identity-ticket-business.yml`); `promtool check rules`; recreate
Prometheus. Reverting this spec only removes the ALERT — it does not touch
either producing domain's own metrics code.

## Escalation

- `HighIdentityAuthorizationDenialRate` (`warning`): opens a ticket against
  user-access-authentication-service's on-call (`service_namespace: user-
  access-authentication`) — domain 08 defines and detects the signal, it does
  not remediate it (ADR-0004).
- `TicketEventDeadLettered` (`critical`, paging): pages ticket-workflow-
  service's on-call (`service_namespace: ticket-workflow`) directly — a stuck
  business event queue is urgent enough to page, not queue as a ticket.

## Post-incident

Link the traceability entry
(`docs/traceability/domains/08-observability-platform/SPEC-OP-025-traceability.md`).
Residual risk: this spec only contracts and alerts on the subset of each
service's already-emitted metrics most directly tied to business-visible
failure (`identity_authorization_decision_total`,
`opsmind_ticket_event_dlq_total`) — the other ~180 already-real `opsmind_*` and
5 other `identity_*` metrics are now contracted (bounded labels enforced) but
have no dedicated alert yet; a follow-up spec could add more if a real
operational need surfaces.
