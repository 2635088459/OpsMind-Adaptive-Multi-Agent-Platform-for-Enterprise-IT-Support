# SPEC-OP-005 Traceability — HTTP And AMQP Trace Propagation

> Domain: `08-observability-platform`
> Phase: `phase-01-unified-signal-contracts`
> Status: implemented
> Verified: 2026-08-30 (validators pass; smoke proves linkage + baggage strip; stack torn down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Implement W3C propagation over HTTP and RabbitMQ publish/consume while
forbidding sensitive baggage.*

| Spec area | Where |
|---|---|
| W3C Trace Context is the only wire format | `signals/trace-propagation.md` §1 + `.yaml` `propagators: [tracecontext, baggage]`, `forbidden_propagators: [b3, b3multi, jaeger, xray, ottrace, datadog]` |
| HTTP propagation (inbound extract / outbound inject / proxy passthrough / sampling-independent) | `signals/trace-propagation.md` §2 + `.yaml` `http.*` |
| AMQP / RabbitMQ publish→consume | `signals/trace-propagation.md` §3 + `.yaml` `amqp.*` (carrier = message header table; PRODUCER / CONSUMER span kinds; child for 1:1, link for fan-out; missing headers → new root; redelivery keeps `traceparent`) |
| Forbidding sensitive baggage | `signals/trace-propagation.md` §4 — allow-list of 5 keys (`correlation_id`, `deployment.environment`, `tenant.id`, `session.kind`, `request.priority`) with value patterns + `max_total_bytes: 1024` + `max_entries: 8`; everything else dropped |
| machine-readable + schema | `signals/trace-propagation.yaml` + `schemas/trace-propagation.schema.json` |
| enforcement | Collector `transform/baggage-contract` deletes every `baggage.*` attribute from resource/span/datapoint/log in all three pipelines; `deny_fields` (SPEC-OP-003) still removes forbidden keys regardless of prefix |
| "schema plus signal-contract tests against Java and Python fixtures" | `signals/fixtures/trace-propagation/` — `http-inbound-conformant.json`, `amqp-message-conformant.json` (pass); `http-b3-nonconformant.json`, `baggage-forbidden-key-nonconformant.json` (reject); checked by `validate-signal-contracts.py` |
| runbook (owner + version) | `runbooks/BrokenTracePropagation.md` (manual-investigation — see §6 of the contract for why no alert) |
| CI gate | `.github/workflows/observability-platform-ci.yml` `layout` job runs `validate-signal-contracts.py` (now also propagation) + self-tests; `config` job `otelcol validate`s the new processor; `smoke` job asserts publish→consume linkage + `baggage.*` strip |
| Traceability | this file + `traceability-entry.yaml` |

Deferred: SDK-side `OTEL_PROPAGATORS` + client/server instrumentation in the services
(domain teams / SPEC-OP-025+); the full Portal→01–07→RabbitMQ single-trace assertion
(SPEC-OP-035 lifecycle E2E); `tracestate` vendor-tag conventions beyond "W3C-valid".

## 2. Why no dedicated alert

"Propagation is broken" has no clean single-series signal — a root span is normal and
a new trace on consume is legal for fan-out. Conformance is covered by the CI fixtures
and the SPEC-OP-035 lifecycle trace. `BrokenTracePropagation.md` is a manual runbook
linked from the SPEC-OP-016 Golden Path dashboard. (`signals/trace-propagation.md` §6.)

## 3. Files added / changed

```text
infrastructure/observability/
  signals/trace-propagation.md                                          NEW
  signals/trace-propagation.yaml                                        NEW
  signals/fixtures/trace-propagation/http-inbound-conformant.json       NEW
  signals/fixtures/trace-propagation/amqp-message-conformant.json       NEW
  signals/fixtures/trace-propagation/http-b3-nonconformant.json         NEW
  signals/fixtures/trace-propagation/baggage-forbidden-key-nonconformant.json  NEW
  schemas/trace-propagation.schema.json                                 NEW
  runbooks/BrokenTracePropagation.md                                    NEW
  collector/base/config.yaml                                            CHANGED  (transform/baggage-contract + pipeline wiring + header note)

scripts/validate-signal-contracts.py                                    CHANGED  (propagation contract + fixtures + collector-wiring checks)
scripts/tests/test_validate_signal_contracts.py                         CHANGED  (7 new propagation tests)
scripts/observability-stack.sh                                          CHANGED  (smoke: PRODUCER+CONSUMER trace with baggage.* attrs)

docs/specs/domains/08-observability-platform/SPEC-OP-005-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-005-traceability.md       NEW (this file)
```

## 4. Commands run and results (2026-08-30 UTC)

| Command | Result |
|---|---|
| `python scripts/validate-observability-layout.py` | 0 errors (2 warnings: `audit_ref` for this file + `BrokenTracePropagation.md` runbook path — cleared on commit) |
| `uv run --with pyyaml python scripts/validate-signal-contracts.py` | 0 errors, 0 warnings — resource-attributes + trace-propagation contracts both OK; propagation `.yaml` shape valid; 2 pass fixtures conformant, 2 reject fixtures non-conformant; `transform/baggage-contract` wired in all 3 pipelines with a `^baggage\.` delete |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **26 passed** (8 layout + 7 governance + 11 signal-contracts — incl. propagation unit tests: parse baggage, conformant HTTP, B3 rejected, forbidden baggage key; e2e: broken propagation fixture → fail, `transform/baggage-contract` unwired → fail) |
| `docker run … otelcol-contrib:0.116.1 validate --config=base --config=local` | exit 0 (`transform/baggage-contract` OTTL parses) |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — pushed a trace with a PRODUCER span (`ticket.created publish`) and a CONSUMER child (`ticket.created process`) carrying `baggage.correlation_id` + `baggage.authorization` + a plain `correlation_id`. Tempo: both spans in one trace, child `parentSpanId` resolves to the producer span; **no `baggage.*` attribute survived**; plain `correlation_id` preserved. SPEC-OP-003/004 assertions still green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Enforcement is **key-prefix** based (`baggage.*`) — if a service copies a baggage value into a differently-named attribute it is not caught here | Medium | the SPEC-OP-003 `deny_fields` list still catches forbidden *keys* (authorization, token, …) under any name; allowed baggage values are expected under their real semantic keys |
| `traceparent` / `tracestate` correctness is not machine-checked at runtime (they are gone by OTLP export) | Medium | fixtures check the carrier shape; SPEC-OP-035 lifecycle E2E asserts one trace spans the request; `BrokenTracePropagation.md` covers manual triage |
| No dedicated alert for a broken hand-off | Low (by design) | documented rationale (§2); dashboard-linked runbook; E2E coverage in SPEC-OP-035 |
| B3 / Jaeger / Datadog headers on an inbound request are not blocked at the edge | Low | OpsMind has no edge proxy that inspects these yet; the fixture + contract make it a review/CI concern; a gateway rule can be added in SPEC-OP-025 |
| Baggage `max_total_bytes` (1024) is effectively unreachable with only the 5 short allow-listed keys | Low | belt-and-suspenders; the real control is the allow-list + `max_entries` |
| `correlation_id` is high-cardinality — safe on spans/logs, must never become a metric label | Low | stated in the contract + `governance cardinality_budgets.*.forbidden_labels`; SPEC-OP-006 enforces the label side |

## 6. Sign-off

W3C Trace Context is the fixed wire format; baggage is an allow-list of 5 non-sensitive
keys with size limits; the Collector strips every `baggage.*` attribute at the
boundary. Java/Python + negative fixtures are under version control and CI-checked, and
the smoke test proves a publish→consume hand-off arrives as one linked trace with
baggage removed and `correlation_id` intact. `SPEC-OP-006` (Metric Naming And
Cardinality Contract) is next in phase-01.
