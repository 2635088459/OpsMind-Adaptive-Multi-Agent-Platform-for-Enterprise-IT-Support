# SPEC-OP-031 Traceability — Telemetry Privacy And Tenant Isolation

> Domain: `08-observability-platform`
> Phase: `phase-07-security-privacy-config-governance`
> Status: implemented
> Verified: 2026-09-01
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Redact at SDK and Collector, isolate tenant
query/retention/encryption, and scan for PII/secrets."*

| Requirement | Where | Scope |
|---|---|---|
| Redact at Collector | `transform/governance` (SPEC-OP-003) + `transform/log-body-redaction` (SPEC-OP-007) | Already real; re-verified live under this spec |
| Redact at SDK | `docs/producer-sdk-redaction-contract.md` + `ADR-0008` | A documented producer contract, not an enforced technical control — stated honestly |
| Tenant query/retention isolation | OTel Collector routing connectors + Loki `runtime_config` + Tempo `per_tenant_override_config` | Real, per-PRODUCING-DOMAIN tenants (9 + shared) — not per-CUSTOMER |
| Encryption | Deliberately out of scope this cycle | TLS on the OTLP ingestion boundary (ADR-0007) remains the only encryption control this domain asserts |
| PII/secret scan | Live push-through-redaction proof | Reuses the existing SPEC-OP-007 mechanism; a fixed, reviewed pattern list, not a general scanner |

## 2. The tenant-model decision — made WITH the user across 3 rounds, not assumed

This domain's own `signals/trace-propagation.yaml` already defines a
`tenant.id` **baggage** key — a genuine per-**customer** concept, its own
comment noting `"slug only; single-tenant today (SPEC-OP-031)"`. Discovering
that fact reshaped this spec's scope mid-build, in stages:

1. **Round 1 (before the baggage contract was found)**: asked the user only
   about per-producing-**domain** routing depth (one Tempo/Loki tenant per
   real domain, vs. a lighter 2-tenant proof). **User chose the full
   9-tenant rollout.**
2. Mid-build, re-reading `trace-propagation.yaml` surfaced the real
   ambiguity: the domain's own pre-existing contract implies per-**customer**
   isolation, not per-domain. Rather than silently keep building the
   already-in-progress per-domain routing on a framing that might be wrong,
   this was stopped and re-surfaced. **Round 2: the user chose to pivot to
   real customer-tenant isolation**, with SDK code added to domain 01 to
   derive and propagate it.
3. Investigating that pivot found a real semantic mismatch, not a simple
   wiring gap: domain 01's `TenantId` (`domain/shared/TenantId.java`) lives
   on individual `TENANT`-scoped `ResourceScope` role assignments — a user
   can hold zero, one, or many — not a single well-defined per-request
   value; and OTel Baggage propagates per-**request** while a producer's
   resource attributes are stamped per-**process**, a structural mismatch
   for deriving one from the other cleanly. Domain 01's own
   `ObservabilityConfig`/`IdentityRequestContext` confirmed zero existing
   Baggage or redaction code to extend — this would have been new,
   unrequested application code in another domain with no LLD section
   asking for it. Surfaced a third time. **Round 3: the user chose to
   revert to per-producing-domain isolation.**

**Final, built architecture**: one real Tempo/Loki tenant per producing
domain (`X-Scope-OrgID` = `service.namespace`, already a SPEC-OP-004
*required* resource attribute) across all 9 domains, plus a `shared`
fallback for anything else. The real customer-`tenant.id` gap is not
resolved by this spec — it is a stated, honest residual limitation (§6),
not silently dropped or fabricated.

## 3. What was built

- **Collector**: two new `connectors` — `routing/traces-tenant` and
  `routing/logs-tenant` (`context: resource`, OTTL `route()` on
  `service.namespace`) — fan the existing single traces/logs pipelines out
  into 18 new per-tenant pipelines (9 domains × traces+logs), each ending in
  its own `X-Scope-OrgID`-stamped exporter (`otlp/tempo-<tenant>` /
  `otlphttp/loki-<tenant>`). `error_mode: propagate` on both connectors — a
  deliberate departure from this file's usual `error_mode: ignore` (§4).
- **Tempo**: `multitenancy_enabled: true` + `overrides.per_tenant_override_config`
  (`tempo/base/overrides.yaml`), two tenants given a real, differentiated
  `compaction.block_retention` (`user-access-authentication`: 168h;
  `observability-platform`: 24h) to prove the override is genuinely
  effective, not merely structurally present.
- **Loki**: `auth_enabled: true` + `runtime_config.file`
  (`loki/base/overrides.yaml`), same two differentiated tenants, same
  `retention_period` values.
- **Loki ruler**: `HighLogSchemaViolationRate` (SPEC-OP-013) moved from the
  single synthetic `fake` tenant directory into 9 real per-tenant copies
  (§4.3).
- **SDK-level redaction**: `ADR-0008` + `docs/producer-sdk-redaction-contract.md`
  — a documented contract every producer domain's own bootstrap is expected
  to follow, not code added to another domain's service (§2's own finding).
- **Collector-level redaction**: unchanged, already real since
  SPEC-OP-003/007 — re-verified live (§5).
- **Validators**: `scripts/validate-collector-pipeline.py`'s existing
  contract (every pipeline starts `memory_limiter`, ends `batch`, includes
  `transform/governance`) now genuinely satisfied by the 18 new fan-out
  pipelines, not bypassed. `scripts/validate-signal-contracts.py`'s stricter
  per-pipeline check was correctly re-scoped to pipelines receiving directly
  from `otlp` (§4.4).

## 4. Real bugs found and fixed via live verification, not static review

### 4.1 A genuinely silent pipeline stall (two compounding causes, not one)

Pushed spans were receiver-accepted (`otelcol_receiver_accepted_spans`
incremented) but never reached **any** exporter — zero
`otelcol_exporter_sent_spans` anywhere, no error in the collector's logs.

- **Cause A**: `tail_sampling`'s pre-existing `decision_wait: 10s`
  (SPEC-OP-010) — early verification queries ran only ~3s after the push,
  well before the tail-sampling decision had even been made.
- **Cause B**: once a properly-tagged, properly-waited span still didn't
  reach one tenant, `error_mode: propagate` (flipped from this file's usual
  `ignore`, specifically to stop silently swallowing this class of failure
  — see the comment left in `config.yaml`) surfaced the real error: Tempo
  2.7.1 does **not** merge a `per_tenant_override_config` entry onto
  `overrides.defaults` field-by-field. Listing a tenant there at all
  **replaces its whole `Limits` struct** — so `user-access-authentication`'s
  entry (originally only `compaction.block_retention`) silently zeroed that
  tenant's own ingestion rate limit, surfacing as `"RATE_LIMITED: ingestion
  rate limit (local: 0 bytes, global: 0 bytes) exceeded"` the moment a real
  span tried to reach it — while `ticket-workflow` (no entry at all, pure
  defaults) ingested fine the whole time.
  - **Fix**: every per-tenant entry in `tempo/base/overrides.yaml` now
    re-states the same `ingestion`/`global` baseline as
    `overrides.defaults` verbatim, alongside its one differentiated field.
    Confirmed via Tempo's own `/status/overrides/<tenant>` endpoint before
    and after.

### 4.2 An over-broad `sed` edit (self-caught mid-fix)

The first attempt to flip `error_mode` on the 2 routing connectors used
`sed -i '' 's/error_mode: ignore/error_mode: propagate/g'`, which matched
all 10 `error_mode: ignore` occurrences in `config.yaml`, including 8
unrelated, already-working transform/filter processors from earlier specs.
Caught immediately via `grep -n "error_mode"`, reverted with the same broad
`sed` back to `ignore`, then redone with a precise line-targeted script
touching only the 2 routing connectors.

### 4.3 Loki's ruler rule orphaned by its own tenant directory

`loki/rules/fake/log-quality.yaml` — `fake` is Loki's fixed synthetic tenant
id, used only when `auth_enabled: false`. Enabling real multitenancy meant
every real producer now stamps its own tenant id instead — the rule would
have silently stopped evaluating against **any** real traffic forever, with
no error, since OSS Loki's ruler has no cross-tenant rule evaluation. Fixed
by duplicating the same, already-reviewed rule content into all 9 real
tenants' own `loki/rules/<tenant>/` directories; `fake/` removed. Confirmed
live: the ruler's own startup log shows a distinct rule-manager instance
starting per real tenant (`user-access-authentication`,
`policy-approval-governance`, `tool-integration`, …), and
`GET /prometheus/api/v1/rules` with a real tenant header now returns the
rule where it previously wouldn't have.

### 4.4 Two governance validators had never accounted for a connector-fed fan-out pipeline

- `validate-collector-pipeline.py` requires every `service.pipelines` entry
  to declare a non-empty `processors` list starting `memory_limiter`,
  ending `batch`, and including `transform/governance` — a real, deliberate
  contract this spec does not get to bypass. The 18 new fan-out pipelines
  genuinely needed `[memory_limiter, transform/governance, batch]`: real
  justification each (backpressure guard on the connector's own fan-out;
  idempotent defense-in-depth re-application of the deny-list floor; a real
  per-tenant re-batch instead of reusing the all-tenants-mixed upstream
  batch), not processors bolted on merely to satisfy a check.
- `validate-signal-contracts.py` had a *stricter*, separate check requiring
  **every** `processors: [` line in the file to contain
  `resourcedetection`/`transform/resource-contract`/`transform/baggage-contract`
  — correct when every pipeline received directly from `otlp`, but wrong
  once fan-out pipelines exist downstream of a connector: those attributes
  are already resolved before the routing connector runs, so re-requiring
  them per tenant would be pure duplicate no-op work, not a real additional
  guarantee. Fixed by scoping both checks (`check_collector`,
  `check_propagation_collector`) to pipelines whose `receivers` include
  `otlp` — the real ADR-0001 ingestion boundary — via a shared
  `_ingest_pipelines()` helper that parses the real YAML structure instead
  of grepping every line in the file.

### 4.5 Every pre-existing smoke-test Tempo/Loki query broke under real multitenancy

`scripts/observability-stack.sh`'s `query_back()` had ~10 Tempo/Loki queries
from `SPEC-OP-002` through `SPEC-OP-028`, none carrying `X-Scope-OrgID`.
Under `auth_enabled`/`multitenancy_enabled: true` these would all now 401 —
a "not found" assertion would have kept passing by accident (a 401 body
doesn't contain the searched string either), which is not the same as the
assertion meaning what it says. Fixed by tagging every existing query with
the real tenant its own push actually routes to (its resource's
`service.namespace`, or `shared` when none was set) rather than leaving
correctness to coincidence.

## 5. Real verification (2026-09-01)

- Full `scripts/observability-stack.sh smoke` run: **SMOKE: PASS** — every
  `SPEC-OP-002`~`030` assertion green (re-verified after this spec's own
  breaking change to every pre-existing Tempo/Loki query), plus:
  - Two traces pushed with different `service.namespace` values
    (`user-access-authentication`, `ticket-workflow`) each reachable **only**
    under their own real tenant (`200`), fully invisible cross-tenant (`404`
    both directions), and rejected with no tenant header at all (`401`).
  - Tempo's `/status/overrides/user-access-authentication` confirmed
    `block_retention: 1w` as the live effective runtime override.
  - Loki's ruler confirmed evaluating `HighLogSchemaViolationRate` for 2
    different real tenants (`shared`, `user-access-authentication`) — not
    the old `fake` tenant.
  - A log body with an embedded fake bearer token + email pushed under the
    `shared` tenant; queried back and confirmed replaced with
    `[REDACTED]`/`[REDACTED_EMAIL]`, `opsmind.log.redacted` stamped — the
    live PII/secret scan proof this spec's acceptance criteria calls for.
- Full validator sweep: `validate-observability-layout.py` 0 err,
  `validate-telemetry-governance.py` 0 err/0 warn,
  `validate-signal-contracts.py` 0 err/0 warn,
  `validate-collector-pipeline.py` 0 err/0 warn, `validate-dashboards.py` 0
  err (1 pre-existing warn), `validate-rule-catalog.py` 0 err (11
  pre-existing warns — unchanged from before this spec).
- `scripts/tests/` (pytest): 82 passed. 3 pre-existing fixtures needed a real
  update for this spec's own config-shape change (2 hardcoded
  `exporters: [otlp/tempo]` → `[routing/traces-tenant]`, 1
  `loki/rules/fake/` path → `loki/rules/shared/`) — no test logic was
  weakened to make them pass.
- `docker compose ... ps`: every container `healthy` throughout, including
  after 3 separate `--force-recreate` cycles (collector, tempo, loki) while
  iterating on the real bugs above.

## 6a. Addendum (2026-09-01, from SPEC-OP-035): a materially clarifying consequence found

Building `SPEC-OP-035`'s first genuinely cross-domain trace surfaced a real
consequence of this spec's per-producing-domain tenant model that this
traceability doc had not stated explicitly: **a single trace whose spans
come from more than one producing domain gets split across more than one
Tempo tenant** (the routing connector routes per-span-batch on
`service.namespace`, not per-trace). Confirmed directly: a 5-span
Identity/MFA→Ticket trace landed 3 spans under the
`user-access-authentication` tenant and 2 under `ticket-workflow` — querying
either tenant alone shows only its own real subset, not "the whole trace."
This is not a bug — it is the correct, intended behavior of per-domain
isolation — but it does mean the real "correlation entry point" for a
cross-domain trace is per-tenant lookup by the same `trace_id`, not a single
omniscient query (OSS Tempo has no cross-tenant query capability). Full
reasoning and the decision to accept this rather than re-architect tenant
isolation: `ADR-0011`.

## 6. Residual risks / honest limitations

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Isolation is per-producing-**domain**, not per-**customer** | Medium — a real, stated scope limit, not a hidden gap | `signals/trace-propagation.yaml`'s own `tenant.id` baggage key remains unrealized; deriving it correctly needs real logic against domain 01's role-assignment-scoped `TenantId` model, a domain-01-owned change this spec does not make (§2) |
| SDK-level redaction is a documented contract, not an enforced control | Medium | no CI in this domain runs against another domain's source; the Collector-side deny-list/redaction remains the actually-tested backstop (ADR-0008) |
| PII/secret "scan" is a fixed, reviewed pattern list (bearer tokens, JWTs, email, credit-card shapes), not a general-purpose scanner | Low | a value that doesn't match one of those shapes reaches the backend unredacted regardless of layer — stated plainly in the SDK contract doc |
| No new encryption mechanism was built this cycle | Low | TLS on the OTLP ingestion boundary (ADR-0007) remains the only encryption control this domain asserts; at-rest encryption for Loki/Tempo storage is unaddressed |
| Tempo's per-tenant override struct-replace behavior (§4.1) is undocumented upstream quirk-knowledge now embedded in this repo's own config comments | Low | any future tenant added to `tempo/base/overrides.yaml` must repeat the full baseline, not just its differentiated field — the comment in that file states this explicitly |

## 7. Sign-off

Real per-producing-domain tenant isolation is live and proven end-to-end
across both Tempo and Loki — genuine query/retention separation, not merely
routing that happens to work. The tenant-model architecture itself was
decided **with** the user across three explicit rounds as real evidence
emerged (a pre-existing customer-tenant contract, then a real semantic
mismatch in deriving it), rather than assumed unilaterally at any point.
Five real bugs were found via live verification and fixed with the root
cause understood, not papered over — including one (the Tempo
override-replace behavior) that would have shipped completely silently
without the `error_mode: propagate` change this spec deliberately keeps.
Redaction is real and layered, with the SDK half honestly scoped as a
contract rather than an enforced guarantee. PII/secret scanning was proven
live against an actual embedded secret + PII payload, not asserted from
config alone.
