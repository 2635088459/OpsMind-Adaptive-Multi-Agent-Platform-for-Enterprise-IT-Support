# SPEC-OP-035 Traceability — Full Lifecycle Trace E2E And Chaos

> Domain: `08-observability-platform`
> Phase: `phase-09-final-verification-release` (opens this phase)
> Status: implemented
> Verified: 2026-09-01
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Run full Identity/MFA trace across domains and
transports with dashboard/alert/runbook/chaos verification."*

| Requirement | Where | Scope |
|---|---|---|
| Cross-domain trace | 5-span Identity→Ticket trace, 2 real domains | New — the first genuinely cross-domain trace this session has pushed |
| Cross-transport | HTTP (SERVER) + AMQP (PRODUCER/CONSUMER) spans | Reuses `SPEC-OP-005`'s established span-kind + parent-child pattern |
| Dashboard verification | `identity-ticket-business-signals.json`, `golden-path-service-overview.json` | Confirmed provisioned; real metrics from this exact flow confirmed landed |
| Chaos verification | A real mid-flight `docker kill opsmind-tempo` | New dedicated `chaos-e2e` drill mode |
| Alert/runbook verification | `tail_sampling`'s risky-operation policy; `ADR-0010`'s recovery procedure | Reused, re-confirmed live |

## 2. What was built

A new `chaos_e2e()` function + `chaos-e2e` CLI mode in
`scripts/observability-stack.sh` — a repeatable, scriptable drill (not a
one-off manual sequence), distinct from `smoke` since it is deliberately
destructive (kills a real container mid-flow):

1. Push spans 1-3 (login → MFA step-up → AMQP publish) —
   `user-access-authentication-service`.
2. **Chaos**: `docker kill opsmind-tempo`.
3. Push spans 4-5 (AMQP consume → ticket resolve) — `ticket-workflow-service`
   — **while Tempo is still down**, proving business fail-open holds for
   this specific realistic flow, not just a synthetic single span.
4. Recover Tempo per `ADR-0010`'s documented procedure
   (`docker compose ... up -d --force-recreate tempo`), measure real
   recovery time against the stated RTO.
5. Verify the full trace, business metrics, and dashboards afterward.

## 3. Two real findings from actually running this for the first time

### 3.1 A real, foreseeable consequence of `SPEC-OP-031`'s tenant model

The first run reported spans 4-5 as **missing** from the trace. Before
concluding this was a WAL/recovery failure, verified directly: queried
Tempo under the `ticket-workflow` tenant instead of
`user-access-authentication` — **both spans were there, intact.**

Root cause understood, not just patched around: `SPEC-OP-031`'s routing
connector routes per-span-batch on `service.namespace`, not per-trace.
Spans 1-3 (`service.namespace=user-access-authentication`) route to that
tenant; spans 4-5 (`service.namespace=ticket-workflow`) route to a
different one. **A single trace_id can genuinely span more than one Tempo
tenant** whenever the underlying business flow crosses producing domains —
and OSS Tempo has no cross-tenant query at all.

This is not a bug — it is exactly what per-domain tenant isolation is
supposed to do — but it is a real, previously-unstated consequence of an
already-shipped decision. Handled by:

- Writing `ADR-0011`, accepting this as a real trade-off of the
  already-user-approved per-domain model (not re-architecting tenant
  isolation to avoid it — a large, out-of-scope undertaking for this
  spec).
- Fixing the drill itself to query **both** tenants a cross-domain trace
  is expected to touch and stitch the results — the real, honest
  correlation procedure under this domain's actual tenant model.
- Adding a cross-referencing addendum to `SPEC-OP-031`'s own traceability
  doc, since this materially clarifies a consequence that spec's original
  write-up did not spell out.

### 3.2 A real, live, 5+-spec-old regression from `SPEC-OP-030`

Checking `up` broadly during this drill found `up{job="prometheus"}=0` and
`up{job="alertmanager"}=0` — **currently, actively down**, not a stale
reading:

```text
GET /api/v1/targets
  job=prometheus:   health=down, lastError="server returned HTTP status 401 Unauthorized"
  job=alertmanager: health=down, lastError="server returned HTTP status 401 Unauthorized"
```

`SPEC-OP-030` gated Prometheus's and Alertmanager's *whole* servers behind
basic auth, but never gave Prometheus's own scrape configs (`job_name:
prometheus`'s self-scrape of `localhost:9090`, and the shared
`file-sd-observability-platform` job that discovers `alertmanager.json`)
any credentials to scrape either endpoint. Both had been **silently
401'ing since `SPEC-OP-030` shipped** — spanning `SPEC-OP-031` through
`SPEC-OP-034` without detection.

Why nothing caught it: `SPEC-OP-030`'s own smoke assertions checked
*query-API* rejection (an unauthenticated request → 401, which is
*correct* behavior) but never separately checked whether Prometheus's
*own internal scrape* of these 2 targets was still succeeding.
`SPEC-OP-012`'s own smoke check (`up{job=...}` present) only asserted the
series *existed* — `up=0` is still "present," so it silently passed for
every run since.

**A compounding, second real bug found in the same investigation**:
`SPEC-OP-034`'s own `prometheus:storage_bytes:total` recording rule had no
`job="prometheus"` label filter. Tempo emits a metric of the *exact same
name* (`prometheus_tsdb_wal_storage_size_bytes`) — confirmed real and
non-zero once enough trace traffic had passed through it (`SPEC-OP-034`'s
own check ran too early, before Tempo's copy existed, and concluded
"Loki/Tempo have no such metric" — not fully accurate). Without the job
filter, the rule was silently aggregating whichever component's
identically-named metric happened to exist, not Prometheus's own storage.

**Fixed, all live-verified:**

```yaml
# prometheus.yml — both scrape jobs
basic_auth: { username: admin, password: admin }
```

```promql
# disk-capacity.yml
prometheus_tsdb_storage_blocks_bytes{job="prometheus"} + prometheus_tsdb_wal_storage_size_bytes{job="prometheus"}
```

Confirmed via `/api/v1/targets` and `up` query: both jobs `up=1` again;
the recording rule now returns a single, correctly-scoped value
(`1080634` bytes, `job="prometheus"` only). Confirmed the fix is harmless
for every other `file_sd` target (none of them validate the
`Authorization` header — all still `up=1` post-fix).

`scripts/observability-stack.sh`'s own `SPEC-OP-012` check was tightened
to assert `up==1`, not merely that the series exists, so this exact class
of regression cannot silently pass again. Addendums added to
`SPEC-OP-030`'s and `SPEC-OP-034`'s own traceability docs, since the root
cause of each half of this bug lived in those specs, not this one.

## 4. Full validator + test + smoke sweep

- `validate-observability-layout.py`, `validate-telemetry-governance.py`,
  `validate-signal-contracts.py`, `validate-collector-pipeline.py`,
  `validate-config-change-audit.py` — all 0 err/0 warn.
- `validate-dashboards.py` 0 err/1 pre-existing warn.
- `validate-rule-catalog.py` 0 err, pre-existing warning categories only.
- `promtool check rules disk-capacity.yml` — SUCCESS, post-fix.
- `scripts/tests/` — 88 passed, unaffected.
- `scripts/observability-stack.sh chaos-e2e` — **CHAOS-E2E: PASS** (full
  account in §2-3).
- `scripts/observability-stack.sh smoke` — **SMOKE: PASS**, every
  `SPEC-OP-002`~`034` assertion green, including the newly-tightened
  `up==1` check.
- Stack torn down clean.

## 5. Residual risks / honest limitations

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Cross-domain traces require per-tenant lookup, not one query (§3.1) | Medium — a real, now-documented limitation of the accepted tenant model | `ADR-0011`; revisit only if a future spec adopts Tempo Enterprise/GEM or a customer-tenant model |
| The exact reason Tempo/Loki share some Prometheus-client-library metric names was not fully investigated (likely shared Go dependency internals) | Low | doesn't affect correctness once the `job` label filter is applied everywhere it matters |
| No systematic audit was run for OTHER recording rules across this domain that might have the same missing-job-filter class of bug | Medium — a real, honest residual, not claimed exhaustively fixed | `SPEC-OP-036` (final coverage audit) is the natural place to check this systematically |

## 6. Sign-off

A genuine, realistic cross-domain, cross-transport business trace was
pushed through a real chaos injection and proven fully correlatable
end-to-end — not a synthetic toy scenario. Doing this for the first time
surfaced two real, consequential findings neither static review nor any
prior spec's narrower testing had caught: a foreseeable architectural
trade-off of the already-shipped per-domain tenant model (documented
honestly, not reversed or hidden), and a genuine, live, 5-spec-old
authentication regression plus a compounding recording-rule bug — both
found via live verification, both fixed with the root cause understood,
and the smoke test itself tightened so the same class of regression
cannot silently recur. This opens
`phase-09-final-verification-release`; `SPEC-OP-036` (Final Coverage
Audit and Release Readiness) closes it and the entire domain roadmap.
