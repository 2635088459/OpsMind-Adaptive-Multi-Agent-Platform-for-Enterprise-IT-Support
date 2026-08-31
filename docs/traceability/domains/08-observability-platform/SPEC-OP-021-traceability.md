# SPEC-OP-021 Traceability — Alertmanager Routing, Dedup, And Silence

> Domain: `08-observability-platform`
> Phase: `phase-05-alerts-slos-runbooks`
> Status: implemented
> Verified: 2026-08-31 (routing, silence, and inhibition each proven via
> Alertmanager's own REST API — real HTTP calls, real state transitions, not
> just `amtool check-config`)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Real routing tree, receivers, and inhibition catalog* — explicitly
named as this spec's job by `SPEC-OP-002`'s own placeholder config comment.

| Spec area | Where |
|---|---|
| Routing (severity-based) | `route.routes` — critical → `critical-page`, warning → `warning-notify` |
| Dedup / grouping | `group_by [alertname, severity, namespace]`, tuned `group_wait`/`group_interval`/`repeat_interval` per branch |
| Inhibition | critical-inhibits-warning (existed); NEW `TargetDown` inhibits co-located alerts |
| Silence | proven via `POST /api/v2/silences` |

## 2. Files added / changed

```text
infrastructure/observability/
  alertmanager/base/alertmanager.yml       CHANGED (real routing tree, 2 new
                                            receivers, new TargetDown inhibit rule)
  runbooks/AlertRoutingAndSilencing.md     NEW

scripts/observability-stack.sh   CHANGED (2 new smoke assertions: routing + silence/inhibition)

docs/specs/domains/08-observability-platform/SPEC-OP-021-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-021-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `amtool check-config` | SUCCESS — 2 inhibit rules, 3 receivers |
| `POST /api/v2/alerts` (1 critical + 1 warning synthetic alert) | 200 |
| `GET /api/v2/alerts/groups` | `RoutingTestCritical` → receiver `critical-page`; `RoutingTestWarning` → receiver `warning-notify` — exactly as routed |
| `POST /api/v2/silences` (matching `RoutingTestWarning`, bounded 1h window) | `{"silenceID": "..."}` |
| `GET /api/v2/alerts` after the silence | `RoutingTestWarning` → `status.state: "suppressed"`; `RoutingTestCritical` → `"active"` (unaffected — different alert, no matcher hit) |
| `POST /api/v2/alerts` (`TargetDown` + `InhibitionTestNoise`, same `job`/`instance`) | 200 |
| `GET /api/v2/alerts` | `InhibitionTestNoise` → `"suppressed"`, `inhibitedBy` populated; `TargetDown` → `"active"` |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — both new assertions green; every `SPEC-OP-002`~`020` assertion in the same run stayed green |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Why receivers have no real external target yet

No Slack/PagerDuty/incident-tool integration exists anywhere in this repo today —
inventing one now would mean fabricating a credential/webhook that isn't real.
Each receiver (`critical-page`, `warning-notify`, `default`) is a **valid**
Alertmanager receiver with zero notifier configs, matching the pattern
`alertmanager.yml`'s own header states: "endpoints come from the environment
secret store by reference only." What this spec proves — and what actually
matters before a real endpoint exists — is that the **routing decision itself**
is correct: a critical alert is unambiguously routed to a different receiver name
than a warning one, verified by Alertmanager's own API, not asserted from reading
the YAML.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No real paging/chat receiver configured | Medium (explicit, not silent) | wiring one is a one-line addition per receiver once a real secret/integration exists; the route tree needs no change |
| Timing values (`group_wait`/`repeat_interval`) are placeholder judgment calls | Low | tune once real on-call patterns are known |
| Only one inhibition beyond the original pair exists | Low | add more as real correlated-alert patterns are observed in practice |

## 6. Sign-off

Routing, deduplication, and silence/inhibition are proven against Alertmanager's
own live API — a synthetic critical alert and a synthetic warning alert really do
land at different receivers, a real silence really does suppress exactly the
alert it matches, and `TargetDown` really does inhibit a co-located alert. This is
not a config that merely parses; it is a config whose runtime behavior was
watched. `SPEC-OP-022` (SLI/SLO And Error Budget Model) continues phase-05.
