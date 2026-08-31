# AlertRoutingAndSilencing

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-021
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>; amtool check-config; recreate alertmanager
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-021-traceability.md

Operational guide for Alertmanager's routing tree, deduplication/grouping, and
silences (`alertmanager/base/alertmanager.yml`) — not a single-alert runbook.

## Routing tree

```text
route (default receiver: default, group_by [alertname, severity, namespace])
├── severity = critical  -> critical-page   (group_wait 10s, repeat 1h)
│   └── owner =~ ".+"    -> critical-page   (nested; a future per-owner target
│                                            overrides here without touching timing)
└── severity = warning   -> warning-notify  (default timing)
```

No receiver has a real external paging/chat target wired yet — each is a valid
Alertmanager receiver with zero notifier configs ("swallow"), routing-correctness
proven via the API (see the traceability doc), independent of where a
notification eventually lands. Wiring a real Slack/PagerDuty endpoint is a
one-line addition to a receiver here, referencing a secret by name — never a
route-tree restructure.

## Deduplication / grouping

`group_by: [alertname, severity, namespace]` means one notification per unique
combination of those three, even if many instances/jobs fire the same alert —
this is what prevents 50 individual `TargetDown` notifications from 50 flapping
targets. `group_wait` (30s default, 10s for critical) is how long Alertmanager
waits to batch an initial group before sending; `group_interval` (5m default, 2m
critical) is the minimum gap between updates to an already-notified group;
`repeat_interval` (3h default, 1h critical) is how often a still-firing group
re-notifies.

## Inhibition rules

1. **Critical inhibits warning** for the same `alertname`+`namespace` — once
   critical has already fired, the warning-level version of the same condition is
   redundant noise.
2. **`TargetDown` inhibits everything else** for the same `job`+`instance` — if a
   scrape target is down, every OTHER alert about that target is a symptom of the
   same root cause (missing data), not an independent condition.

## Creating a silence (real, audited control-plane action)

Per `forbidden-business-writes.md` §2 and ADR-0005, a silence is an explicitly
allowed control-plane action (not a business write):

```sh
curl -X POST http://localhost:9093/api/v2/silences -H 'Content-Type: application/json' -d '{
  "matchers": [{"name":"alertname","value":"<AlertName>","isRegex":false}],
  "startsAt": "<ISO8601>", "endsAt": "<ISO8601>",
  "createdBy": "<your name>", "comment": "<why>"
}'
```

Always set a bounded `endsAt` and a real `comment` — an unexplained, indefinite
silence is exactly the kind of blind spot this domain's own alerting exists to
prevent. Check `GET /api/v2/silences` before creating a new one to avoid
duplicating an existing suppression.

## Verifying routing/grouping/silence behavior

`GET /api/v2/alerts/groups` shows every active alert group AND which receiver it
routed to (`receiver.name`) — the fastest way to confirm a routing change did what
you expect before relying on it in an incident. `GET /api/v2/alerts` shows each
alert's `status.state` (`active`/`suppressed`/`unprocessed`) and, when suppressed,
`status.inhibitedBy` / the matching silence ID.

## Rollback

`git revert` the routing/inhibition change; `amtool check-config`; recreate
`alertmanager`.
