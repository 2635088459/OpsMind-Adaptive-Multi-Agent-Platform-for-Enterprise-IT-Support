# SPEC-OP-007 Traceability — Structured Log And Redaction Contract

> Domain: `08-observability-platform`
> Phase: `phase-01-unified-signal-contracts`
> Status: implemented
> Verified: 2026-08-31 (validators pass; otelcol validate passes after a real OTTL
> escaping fix; promtool/loki verify-config pass; full docker-compose smoke proves
> value-level redaction + linkage-violation stamping against a real Loki; stack torn
> down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Define JSON log schema, trace linkage, severity, event codes,
redaction, sampling, and multiline handling.*

| Spec area | Where |
|---|---|
| Log record shape | `signals/structured-log.md` §1 — `time_unix_nano`, `severity_number`/`severity_text`, `body` (string), linkage attribute, resource attributes (SPEC-OP-004) |
| Severity mapping | §2 + `.yaml` `severity_map` — 6 ranges (1-4/5-8/9-12/13-16/17-20/21-24 → trace/debug/info/warn/error/fatal); `severity_number == 0` always non-conformant (`unspecified_severity_number`) |
| Event codes | §3 + `.yaml` `event_code` — `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$`, attribute `event.code`, recommended not required |
| Trace/correlation linkage | §4 + `.yaml` `linkage.any_of: [trace_id, correlation_id]` — as attributes, matching this repo's existing convention (governance `allow_fields.log`), not the native OTLP `LogRecord.trace_id` byte field |
| Multiline handling | §5 + `.yaml` `multiline` — one logical event per `LogRecord`; `max_body_chars` 32768; Collector truncates + stamps rather than dropping |
| Redaction (value-level) | §6 + governance `log_body_redaction` (6 named patterns) + `.yaml` `redaction` reference — the part SPEC-OP-004/005/006 never needed, since `deny_fields` (SPEC-OP-003) only deletes attribute **keys** |
| Sampling | §7 + `.yaml` `sampling_intent` — per-level → governance `retention_class`; rate-based dropping itself is `SPEC-OP-011` |
| enforcement | Collector `transform/log-schema-contract` (violation stamping + truncation) + `transform/log-body-redaction` (value scrubbing), both logs-pipeline only |
| "schema plus signal-contract tests against Java and Python fixtures" | `signals/fixtures/structured-log/` — 2 conformant, 5 nonconformant; checked by `validate-signal-contracts.py` |
| rules + runbook (owner + version) | No new Prometheus rule files — see §4 "Deliberately no live alert" below; `runbooks/StructuredLogContractViolation.md` |
| CI gate | `layout` job runs `validate-signal-contracts.py` (now also structured-log) + `validate-telemetry-governance.py` (now also `log_body_redaction`) + self-tests; `config` job `otelcol validate`s the two new processors |
| Traceability | this file + `traceability-entry.yaml` |

Deferred: producer-side logging SDK bootstrap (severity mapping, linkage injection)
(domain teams / `SPEC-OP-025`+); a live Prometheus/Loki-ruler alert on log-schema
violations (`SPEC-OP-008`+ pipeline structure / `SPEC-OP-013` Loki backend /
`SPEC-OP-021` Alertmanager routing — see the honest-scope note below); rate-based log
sampling under backpressure (`SPEC-OP-011`).

## 2. Files added / changed

```text
infrastructure/observability/
  signals/structured-log.md                                              NEW
  signals/structured-log.yaml                                            NEW
  signals/fixtures/structured-log/conformant-info-trace-linkage.json               NEW
  signals/fixtures/structured-log/conformant-error-correlation-only.json           NEW
  signals/fixtures/structured-log/nonconformant-unspecified-severity.json          NEW
  signals/fixtures/structured-log/nonconformant-missing-linkage.json               NEW
  signals/fixtures/structured-log/nonconformant-bad-event-code.json                NEW
  signals/fixtures/structured-log/nonconformant-raw-secret-in-body.json            NEW
  signals/fixtures/structured-log/nonconformant-oversized-body-not-truncated.json  NEW
  schemas/structured-log.schema.json                                     NEW
  runbooks/StructuredLogContractViolation.md                             NEW
  collector/base/config.yaml                CHANGED (transform/log-schema-contract +
                                             transform/log-body-redaction, logs pipeline)
  governance/telemetry-governance.yaml       CHANGED (v1.3.0: log_body_redaction section;
                                             allow_fields.log.recommended += event.code)
  schemas/telemetry-governance.schema.json   CHANGED (log_body_redaction property)

scripts/validate-signal-contracts.py         CHANGED (structured-log contract + governance-
                                              sync + fixtures + collector-wiring checks)
scripts/validate-telemetry-governance.py     CHANGED (log_body_redaction shape + baseline-
                                              concept coverage + collector-sync checks)
scripts/tests/test_validate_signal_contracts.py       CHANGED (13 new tests)
scripts/tests/test_validate_telemetry_governance.py   CHANGED (2 new E2E tests)
scripts/observability-stack.sh               CHANGED (smoke: PII/secret log + unlinked log
                                              + Loki assertions; existing OP-002 log now
                                              also sets severityNumber:9)

docs/specs/domains/08-observability-platform/SPEC-OP-007-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-007-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `uv run --with pyyaml python scripts/validate-observability-layout.py` | 0 errors (warnings for the new runbook/audit_ref paths cleared once this file + the runbook existed) |
| `uv run --with pyyaml python scripts/validate-telemetry-governance.py` | 0 errors, 0 warnings — `log_body_redaction` shape OK, all 6 baseline concepts (bearer/jwt/email/card/ssn/password) covered, every pattern's regex source verified verbatim inside `transform/log-body-redaction` |
| `uv run --with pyyaml python scripts/validate-signal-contracts.py` | 0 errors, 0 warnings — structured-log shape OK; linkage/event.code attributes sync with `allow_fields.log.recommended`; 2 pass + 5 reject fixtures behave; both new processors wired into the logs pipeline; collector regex covers every governance redaction pattern |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **46 passed** (8 layout + 9 governance incl. 2 new + 29 signal-contracts incl. 7 `StructuredLogUnitTests` + 4 new structured-log E2E) |
| `docker run … otelcol-contrib:0.116.1 validate` (with `OTEL_TEMPO_ENDPOINT`/`OTEL_LOKI_ENDPOINT`/`OTEL_DEPLOYMENT_ENVIRONMENT` set) | **first run failed** — "invalid quoted string" on every `\s`/`\.`/`\b` inside the two new processors' OTTL statements; fixed by doubling every backslash (`\\s`, `\\.`, `\\b`, `\\S`) since OTTL's own string lexer needs it independently of the single-quoted YAML wrapper. Re-run: exit 0 |
| `docker run … promtool check config /etc/prometheus/prometheus.yml` + existing rule files | SUCCESS — unaffected (no new Prometheus rule files this spec, see §4) |
| `docker run … loki -verify-config` | `msg="config is valid"` — unaffected |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — pushed a log with body `"login succeeded for jane.doe@example.com token=SHOULD-BE-REDACTED-1234"` (linked by `trace_id`) and a second log with **no** `trace_id`/`correlation_id` attribute. Loki query for `service_name="op-007-smoke"` confirmed: neither the raw email nor the raw token value reached Loki; the body carries `[REDACTED_EMAIL]` and `token=[REDACTED]` verbatim; `opsmind.log.redacted` is stamped on the first record; `opsmind.log.violation=missing:trace_linkage` is stamped on the second. All SPEC-OP-002/003/004/005/006 assertions in the same run stayed green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Deliberately no live Prometheus/Loki-ruler alert

Every earlier phase-01 spec shipped a recording+alerting rule pair
(`rules/recording/*.yml` + `rules/alerting/*.yml`) because a metric-pipeline bridge
already existed (`resource_to_telemetry_conversion` on the METRICS exporter). Logs have
**no equivalent bridge** in the current pipeline shape: a log-only condition (missing
linkage, bad severity, a redaction hit) cannot reach Prometheus without either (a) a new
Collector `count`/routing connector — pipeline-structure scope this file's own header
reserves for `SPEC-OP-008`+, or (b) wiring Loki's ruler (already present in
`loki/base/loki.yml` — `ruler.storage.local.directory: /loki/rules` — but with **no**
rules mounted and **no** `alertmanager_url` configured) to notify Alertmanager, which is
backend-configuration scope (`SPEC-OP-013`/`SPEC-OP-015`) plus routing scope
(`SPEC-OP-021`). Inventing either now, just to ship a rule file, would be unenforceable
theater. Detection today is the LogQL queries in `runbooks/StructuredLogContractViolation.md`;
the automated alert is explicitly listed as deferred, not silently dropped.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| No automated alert on log-schema violations or redaction hits | Medium | manual LogQL runbook today; `SPEC-OP-008`+ (Collector pipeline structure) or `SPEC-OP-013`/`SPEC-OP-021` (Loki ruler → Alertmanager) close the gap |
| `log_body_redaction` is a **fixed pattern list**, not a general PII classifier | Medium | 6 concept classes (bearer/JWT/email/card/SSN/key=value secret) catch the common shapes; a genuinely new secret/PII shape needs a one-line governance PR + matching OTTL statement, exactly like `SPEC-OP-006`'s cardinality regex |
| `replace_pattern`'s RE2 engine has no lookahead/lookbehind | Low | none of the 6 patterns need one; a future pattern that does would need a different approach (multi-statement chaining) |
| Producer-side severity/linkage bootstrap not yet touched | Medium | this spec only builds and enforces the CONTRACT; wiring every service's logging SDK to emit it correctly is `SPEC-OP-025`+ |
| Multiline enforcement is body-SIZE only, not "one exception = one record" in a machine-checkable way | Low | OTLP push (ADR-0001) makes this fundamentally a producer SDK contract; the Collector can only bound size, which it does |
| `credit_card` / `secret_kv` patterns are heuristic and can both false-positive (a 13-16 digit non-card number) and false-negative (a formatted card with unusual spacing) | Low | acceptable for defense-in-depth; F7 already forbids producers from emitting these values regardless of whether the pattern catches them |

## 6. Sign-off

JSON log shape, severity mapping, event-code taxonomy, trace/correlation linkage,
multiline/oversized-body handling, and value-level redaction of free-text log bodies
are defined (human + machine + schema), aligned with the governance rulebook (v1.3.0),
and enforced: the Collector stamps schema violations without ever dropping a record,
truncates oversized bodies, scrubs 6 classes of secret/PII patterns from log bodies,
and stamps when it does — all proven against a real OTel Collector + Loki in the smoke
test, not just fixtures. This closes **phase-01 (Unified Signal Contracts,
`SPEC-OP-004`~`007`)** for domain 08. `SPEC-OP-008` (OTLP Collector Gateway) opens
phase-02 (Collector Intake And Processing).
