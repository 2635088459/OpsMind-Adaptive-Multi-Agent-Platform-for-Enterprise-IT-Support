# Structured Log And Redaction Contract

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-007
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: standard
> runbook: runbooks/StructuredLogContractViolation.md
> rollback: git revert <sha>; redeploy otel-collector
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-007-traceability.md

Logs are the highest-risk signal: they carry free-text bodies that engineers write
under pressure, and free text is exactly where a stack trace, a raw SQL statement, an
email address, or a bearer token ends up. This contract fixes the JSON log shape,
severity mapping, event codes, trace/correlation linkage, multiline handling, and —
the part the other three signal contracts do not need — **value-level** redaction of
free-text log bodies (SPEC-OP-004/005/006 only ever deleted attribute *keys*).

Machine-readable form: [`structured-log.yaml`](structured-log.yaml)
(schema [`../schemas/structured-log.schema.json`](../schemas/structured-log.schema.json)).
Fixtures: [`fixtures/structured-log/`](fixtures/structured-log/).
Body-redaction patterns are governed centrally in
[`../governance/telemetry-governance.yaml`](../governance/telemetry-governance.yaml)
`log_body_redaction` — this file's `redaction.collector_processor` name must match
(`validate-signal-contracts.py`).

## 1. Log record shape

Every log record OTLP-exported to the Collector is a `LogRecord` with:

| Field | Requirement |
|---|---|
| `time_unix_nano` | required; the event time, not the export time |
| `severity_number` | required; 1–24, never `0` (`SEVERITY_NUMBER_UNSPECIFIED`) — see §2 |
| `severity_text` | required; canonical short form from §2 |
| `body` | required; a string. Structured payloads go in `attributes`, not a nested body object |
| `attributes["trace_id"]` and/or `attributes["correlation_id"]` | at least one required — see §4 |
| `attributes["event.code"]` | recommended for anything actionable — see §3 |
| resource attributes | per `SPEC-OP-004` (`service.name`, `service.version`, ...) |

`attributes.log.level` (governance `allow_fields.log`) is the human-readable alias of
`severity_text`; set one, the SDK derives the other.

## 2. Severity mapping

OTel `severity_number` ranges map onto one canonical `level` each
(`structured-log.yaml` `severity_map`):

| `severity_number` range | `severity_text` prefix | `level` | Typical use |
|---|---|---|---|
| 1–4 | `TRACE` | `trace` | step-by-step execution detail |
| 5–8 | `DEBUG` | `debug` | diagnostic detail, high volume |
| 9–12 | `INFO` | `info` | normal operation, state transitions |
| 13–16 | `WARN` | `warn` | recoverable anomaly, degraded path taken |
| 17–20 | `ERROR` | `error` | request/operation failed |
| 21–24 | `FATAL` | `fatal` | process cannot continue |

A record with `severity_number == 0` (or missing) fails the contract; the Collector
stamps it rather than dropping it (ADR-0004 — same "never drop, always visible"
posture as SPEC-OP-004's resource violation).

## 3. Event codes

`attributes["event.code"]` is a dotted, lowercase, machine-greppable taxonomy for
anything an operator will search for or alert on: `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$`
— e.g. `ticket.created`, `agent.tool_call.failed`, `auth.mfa.step_up_required`.
Not every log line needs one (a debug trace does not); any record that represents a
state transition, a failure, or a security-relevant event SHOULD carry one.

## 4. Trace and correlation linkage

A log record MUST carry at least one of `attributes["trace_id"]` or
`attributes["correlation_id"]` (both, when available). This repo's convention keeps
these as plain attributes rather than the native OTLP `LogRecord.trace_id` byte field,
matching how `SPEC-OP-005` already treats `correlation_id` — see
`governance/telemetry-governance.yaml` `allow_fields.log`. A record with neither is a
correlation dead end: it can never be joined back to the request that produced it.

## 5. Multiline handling

Ingestion is OTLP push (ADR-0001) — there is no file-tailing multiline problem. The
rule instead binds the producer SDK: one logical event (an exception with its stack
trace, a multi-statement diagnostic dump) is **one** `LogRecord` with embedded `\n` in
`body`, never split across several records. A body over
`structured-log.yaml` `multiline.max_body_chars` (32 768) is truncated by the Collector
(`transform/log-schema-contract`), which stamps `attributes["opsmind.log.truncated"] =
"true"` rather than silently cutting it — see the runbook if you see that attribute.

## 6. Redaction — value-level, not just keys

`SPEC-OP-003`'s `transform/governance` deletes deny-listed attribute **keys**
(`authorization`, `password`, ...). That does nothing for a secret or PII value sitting
inside a free-text `body` string, e.g. `"login failed for jane.doe@example.com with
password=hunters2"`. `governance/telemetry-governance.yaml` `log_body_redaction`
defines named regex patterns + replacements (bearer tokens, JWTs, email addresses, card
numbers, SSNs, `key=value` secrets); the Collector's `transform/log-body-redaction`
processor applies `replace_pattern(body, ...)` for each, in the logs pipeline only, and
stamps `attributes["opsmind.log.redacted"] = "true"` when any pattern matched so the
redaction is itself observable.

This is **defense in depth**, not a license to log secrets: F7
([`forbidden-business-writes.md`](../docs/forbidden-business-writes.md)) already
forbids emitting them; a producer whose logs regularly trip this processor should fix
the logging call, not rely on the Collector.

## 7. Sampling

Sampling is volume/backpressure policy, not a redaction concern; the retention class a
severity maps to (`retention_classes` in governance) states the *intent* here — actual
rate-based dropping under load is Collector processing scope (`SPEC-OP-011`):

| `level` | Retention class | Sampling intent |
|---|---|---|
| `trace`, `debug` | `debug` | first to sample/drop under backpressure |
| `info`, `warn` | `standard` | kept at full rate in local/ci; rate-limited per-service in prod if volume requires it |
| `error`, `fatal` | `standard` | never sampled away |

## 8. Enforcement

| Layer | Control |
|---|---|
| Producer | emit the shape in §1, set `severity_number`/`severity_text` together, never log raw secrets/PII (F7) |
| Collector | `transform/log-schema-contract` stamps `opsmind.log.violation` for missing linkage / bad event-code / unspecified severity, and truncates + stamps oversized bodies; `transform/log-body-redaction` scrubs body values per governance `log_body_redaction`, stamping `opsmind.log.redacted` |
| CI | `scripts/validate-signal-contracts.py` — `.yaml` shape, governance sync (`allow_fields.log`, `log_body_redaction`), fixture records pass/fail the severity + linkage + event-code + body-safety rules, Collector wiring + governance-pattern coverage |
| Runtime | no automated Prometheus/Loki-ruler alert ships with this spec — logs have no metrics bridge in the phase-01 pipeline shape (adding one is Collector pipeline-structure scope, `SPEC-OP-008`+/`SPEC-OP-013` ruler wiring); triage today is the LogQL queries in the runbook |

## 9. Schema evolution

New event-code namespace / new redaction pattern / new recommended attribute →
additive, PR + `platform-observability` + the log family's semantic owner
(`signal_owners` `logs.*` in governance). Removing a redaction pattern, narrowing the
severity map, or making `event.code` mandatory → breaking per `governance
schema_review`; bump this file's `version`.
