# StructuredLogContractViolation

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-007
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: standard
> runbook: self
> rollback: git revert <sha>; redeploy otel-collector
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-007-traceability.md

Covers the two attributes `transform/log-schema-contract` and
`transform/log-body-redaction` stamp on a `LogRecord` in Loki:
`opsmind.log.violation` (missing trace/correlation linkage, unspecified severity, a
malformed `event.code`, or an oversized body) and `opsmind.log.redacted` /
`opsmind.log.truncated`.

## Impact

**Observability only.** A schema violation never blocks ingestion (ADR-0004 — the
record still reaches Loki) and body redaction/truncation never blocks the business
request path. The cost is a harder-to-correlate or partially-scrubbed log line, not a
production incident.

## Detection

There is no automated Prometheus or Loki-ruler alert shipped with this spec — logs
have no metrics bridge in the phase-01 Collector pipeline shape (adding one is
pipeline-structure scope, deferred to `SPEC-OP-008`+, and Loki ruler→Alertmanager
wiring is deferred to `SPEC-OP-013`/`SPEC-OP-021`). Detect today via LogQL in Grafana
Explore or `curl`:

```logql
{service_name="<svc>"} | opsmind_log_violation != ""
{service_name="<svc>"} | opsmind_log_redacted = "true"
{service_name="<svc>"} | opsmind_log_truncated = "true"
```

(Loki turns OTLP log attributes into structured metadata / labels per
`allow_structured_metadata`, so the dotted attribute name becomes the underscored
label above.)

## Triage

1. **`opsmind.log.violation = "missing:trace_linkage"`** — the producer emitted a log
   with neither `trace_id` nor `correlation_id`. Find the emitting service/line; it is
   almost never intentional (background jobs still have a correlation id).
2. **`= "missing:severity"`** — `severity_number` was `0` or absent. Check the SDK's
   logging bridge — most logging libraries have an "unknown level" fallback that maps
   here by mistake.
3. **`= "bad:event_code"`** — `event.code` doesn't match
   `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$` (dotted lowercase, e.g.
   `ticket.created`). Usually CamelCase or a free-text string was passed where a code
   was expected.
4. **`opsmind.log.truncated = "true"`** — body exceeded `structured-log.yaml`
   `multiline.max_body_chars` (32 768). Check whether the producer is emitting an
   unbounded dump (e.g. a full request/response body) rather than a bounded event.
5. **`opsmind.log.redacted = "true"`** — a governance `log_body_redaction` pattern
   matched the body **before** the Collector scrubbed it. This means a producer logged
   something matching a known secret/PII shape (bearer token, JWT, email, card number,
   SSN, `key=value` credential). The Collector caught it, but the producer must stop
   emitting it — this is not "handled," it's "this time it was caught."

## Mitigation

- Fix the producer's logging call (add linkage, fix severity mapping, fix the event
  code, bound the body, stop logging the secret/PII value). None of this is a
  Collector-side fix beyond what's already in place.
- If a **new** secret/PII shape is slipping past every existing `log_body_redaction`
  pattern, add a pattern to `governance/telemetry-governance.yaml` `log_body_redaction`
  + the matching `replace_pattern` statement in
  `collector/base/config.yaml : transform/log-body-redaction`, PR + platform-observability
  review (`validate-telemetry-governance.py` / `validate-signal-contracts.py` fail CI if
  they diverge).

## Resolution

Producer emits conformant records: linkage present, severity mapped, event codes
dotted-lowercase, no oversized bodies, no secret/PII shapes in free text.

## Rollback

`git revert` the offending collector/governance change; `otelcol validate` the merged
config; recreate `otel-collector`.

## Escalation

`platform-observability` → the log family's semantic owner
(`governance/telemetry-governance.yaml` `signal_owners` `logs.*` →
`originating-domain-team`). A new secret/PII shape reaching Loki unredacted (i.e. a
`log_body_redaction` pattern gap, not just a violation stamp) is treated as a
security-relevant miss, not a routine bug.

## Post-incident

If a whole service class is affected, tighten the shared logging bootstrap SDK
(severity mapping, linkage injection) rather than relying on the Collector stamp, and
record the gap in the `SPEC-OP-025`+ cross-domain observability contract for that
domain.
