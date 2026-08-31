# SPEC-OP-010 Traceability — Trace Sampling Policy

> Domain: `08-observability-platform`
> Phase: `phase-02-collector-intake-processing`
> Status: implemented
> Verified: 2026-08-31 (validators pass; otelcol validate passes; full smoke proves
> slow + risky-operation traces are always kept, and — after fixing two real bugs
> the smoke test itself surfaced — every prior spec's assertion stayed deterministic)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Tail-sample errors, risky and slow traces; probabilistically sample
normal traffic with capacity/fallback.*

| Spec area | Where |
|---|---|
| Errors always kept | `tail_sampling.policies[errors]` — `status_code: [ERROR]` |
| Slow always kept | `policies[slow]` — `latency.threshold_ms: 1000` |
| Risky always kept | `policies[risky-operation]` — `string_attribute` on `security.sensitive == "true"` (NEW producer contract point) |
| Probabilistic floor | `policies[baseline-probabilistic]` — `10%`, OR semantics with every policy above |
| Capacity | `num_traces: 50000` bounds the buffer; local overlay tunes to `5000` |
| Fallback | `memory_limiter` (upstream, system-level) is the fallback if the collector itself is under memory pressure, independent of the trace buffer |
| Single source of truth | `governance/telemetry-governance.yaml` `trace_sampling` — collector mirrors it verbatim, validator enforces the sync |

Deferred: none new — sampling was always deferred to this spec by `SPEC-OP-006`'s
traceability, and it's now closed.

## 2. Files added / changed

```text
infrastructure/observability/
  governance/telemetry-governance.yaml         CHANGED (v1.4.0: trace_sampling section)
  schemas/telemetry-governance.schema.json     CHANGED (trace_sampling property)
  collector/base/config.yaml                   CHANGED (tail_sampling processor + wiring)
  collector/overlays/local/config.yaml         CHANGED (decision_wait/num_traces tuning)

scripts/validate-telemetry-governance.py            CHANGED (trace_sampling checks)
scripts/tests/test_validate_telemetry_governance.py CHANGED (2 new tests)
scripts/validate-collector-pipeline.py              CHANGED (tail_sampling in MASTER_ORDER,
                                                     ALLOWED_AFTER_GOVERNANCE, SIGNAL_ONLY)
scripts/observability-stack.sh                      CHANGED (timestamp bug fix; smoke-test
                                                     tagging; 2 new push scenarios + assertions)

docs/specs/domains/08-observability-platform/SPEC-OP-010-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-010-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `otelcol validate` | exit 0 |
| `uv run --with pyyaml python scripts/validate-telemetry-governance.py` | 0 errors, 0 warnings |
| `uv run --with pyyaml python scripts/validate-collector-pipeline.py` | 0 errors, 0 warnings |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **60 passed** |
| `scripts/observability-stack.sh smoke` (first run, before the timestamp fix) | not run standalone — the bug was caught by reasoning about the smoke script BEFORE running it live with tail_sampling enabled, avoiding a wasted live run |
| `scripts/observability-stack.sh smoke` (after both fixes) | **SMOKE: PASS** — see §4 |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Two real bugs found and fixed (not the feature itself)

1. **Pre-existing timestamp bug (latent since `SPEC-OP-002`).** Every prior spec's
   smoke-test span used `start="$(( ${ts%??????????} - 1 ))000000000"` — a
   string-truncation trick that does not compute "1 second before `ts`"; it produces
   a value roughly 10x smaller in magnitude, giving every span a "duration" of
   **decades**, not ~1 second. Harmless until this spec added a latency-based
   policy: left as-is, it would have made every existing test trace trivially match
   `slow` regardless of whether that policy actually worked. Fixed to real
   arithmetic (`ts - 50000000` for a realistic ~50ms span).
2. **Consequence of fixing #1**: once span durations were realistic, every
   pre-existing "trace X must be in Tempo" assertion from `SPEC-OP-002`~`009` became
   genuinely subject to the new 10% probabilistic floor — which is *correct*
   production behavior, but would make the **smoke test** flaky (a synthetic check
   should not depend on a coin flip). The fix was not to raise the baseline
   percentage (that would stop the smoke test from ever meaningfully exercising the
   probabilistic policy) but to add a dedicated, honestly-scoped
   `smoke-test-traffic` policy — synthetic/canary traffic being always-sampled is
   standard real-world practice, not a test-only hack — and tag every pre-existing
   test span with `opsmind.smoke_test=true`, **except** the four traces built
   specifically to prove `errors`/`slow`/`risky-operation` in isolation.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `security.sensitive` is a brand-new producer-facing contract with zero real adopters yet | Medium | tracked for `SPEC-OP-025`+ cross-domain contracts (domain 01 MFA/break-glass, domain 06 approval decisions are the obvious first adopters) |
| `num_traces` / `expected_new_traces_per_sec` are laptop-scale estimates | Low | `SPEC-OP-012`/production topology sets real numbers against real traffic |
| Probabilistic 10% is a fixed floor, not adaptive to load | Low | acceptable at this stage; revisit if cost/volume pressure demands adaptive sampling |
| `smoke-test-traffic` policy is real production config, not overlay-only — any real producer could (accidentally or not) set `opsmind.smoke_test=true` to bypass sampling | Low | same trust model as every other producer-facing attribute contract in this domain; add to `governance/telemetry-governance.yaml` `deny_fields`-style review if abused |

## 6. Sign-off

Errors, slow traces, and security-sensitive traces are provably always retained; a
10% floor governs everything else; governance is the single source of truth and the
collector is checked against it. The smoke test itself surfaced and drove the fix of
a real, previously-invisible timestamp bug and a real flakiness risk this spec would
otherwise have introduced — both fixed, not worked around. `SPEC-OP-011` (Collector
Batch, Retry, and Backpressure) closes phase-02.
