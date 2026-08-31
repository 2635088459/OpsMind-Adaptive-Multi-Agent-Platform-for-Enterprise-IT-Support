# HTTP and AMQP Trace Propagation

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-005
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: standard
> runbook: runbooks/BrokenTracePropagation.md
> rollback: git revert <sha>; redeploy otel-collector
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-005-traceability.md

A single OpsMind request must be one trace from Portal / API Gateway through domains
01–07, RabbitMQ, PostgreSQL, and external connectors. This contract fixes how trace
context crosses a process boundary.

Machine-readable form: [`trace-propagation.yaml`](trace-propagation.yaml)
(schema [`../schemas/trace-propagation.schema.json`](../schemas/trace-propagation.schema.json)).
Fixtures: [`fixtures/trace-propagation/`](fixtures/trace-propagation/).

## 1. Propagators

- **W3C Trace Context** (`traceparent`, `tracestate`) is the **only** wire format.
- **W3C Baggage** (`baggage`) is allowed, restricted to the §4 allow-list.
- Every SDK sets `OTEL_PROPAGATORS=tracecontext,baggage` (nothing else).
- **Forbidden**: `b3`, `b3multi`, `jaeger`, `xray`, `ottrace`, `datadog`. A service
  MUST NOT emit or require these headers. Proxies / gateways MUST pass `traceparent`
  and `tracestate` through unmodified.

## 2. HTTP

| Direction | Rule |
|---|---|
| Inbound (server) | Extract context from `traceparent` / `tracestate` before creating the server span. A request with no `traceparent` starts a new trace (root). A malformed `traceparent` is ignored (new trace), never rejected — availability first. |
| Outbound (client) | Inject the current context as `traceparent` / `tracestate` (+ `baggage` per §4) on every request to another OpsMind service, RabbitMQ management, or an external connector. |
| Gateways / reverse proxies | Pass `traceparent` / `tracestate` through verbatim. Do not terminate or regenerate. |
| Sampling | Propagation is sampling-independent: context flows even when the local span is not recorded, so a downstream sampler stays consistent. The sampled flag lives in the `traceparent` `flags` byte. |

## 3. AMQP / RabbitMQ

| Step | Rule |
|---|---|
| Publish | Inject `traceparent`, `tracestate`, and (if present) `baggage` into the AMQP **message header table** using those exact keys. The publish span is `SpanKind.PRODUCER`. |
| Consume | Extract context from the message headers. The consume span is `SpanKind.CONSUMER`. |
| Linkage | For a normal 1:1 hand-off the consumer span is a **child** of the producer span (same trace). For fan-out / batch consume, use a **span link** to each producer span and start a new trace per consumed message only if a child would be misleading. |
| Missing headers | Consume starts a new trace (root consumer span) — never drop or reject the message. |
| Redelivery | A redelivered message keeps its original `traceparent`; the retry is a new consumer span in the same trace. |

## 4. Baggage

Baggage is for **cross-cutting, non-sensitive** context only.

### Allowed keys (allow-list — everything else is dropped)

| Key | Format | Cardinality | Notes |
|---|---|---|---|
| `correlation_id` | `^([A-Z]{2,5}-\d{1,10}\|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$` | high (per request) | the OpsMind business key — a ticket display id (`INC-2048`) or a UUID for non-ticket flows. Also set as the span attribute `correlation_id`. **Never a metric label.** |
| `deployment.environment` | `^(local\|ci\|staging\|production)$` | bounded | |
| `tenant.id` | `^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$` | bounded | slug only; single-tenant today (SPEC-OP-031) |
| `session.kind` | `^(interactive\|batch\|system\|scheduled)$` | bounded | the **kind** of session, never a session id |
| `request.priority` | `^(low\|normal\|high\|critical)$` | bounded | |

### Limits

- `max_total_bytes`: **1024** (well under the W3C 8192 recommendation).
- `max_entries`: **8**.
- Values are UTF-8, no control characters, URL-encoded per the W3C Baggage spec.

### Forbidden in baggage (non-exhaustive — the governance `deny_fields` list applies)

Tokens, `authorization`, cookies, passwords, API keys, MFA/OTP material, raw prompts,
raw user text, PII (email, SSN, PAN), `user.id`, `session.id`, `ticket.id` as a raw
DB id (use `correlation_id`), anything a downstream would log verbatim.

## 5. Enforcement

| Layer | Control |
|---|---|
| Producer | `OTEL_PROPAGATORS=tracecontext,baggage`; a `BaggageSpanProcessor` (if used) is configured with the §4 allow-list only |
| Collector | `transform/baggage-contract` deletes every `baggage.*` attribute from resource / span / datapoint / log — baggage is a transport mechanism and must not land on a span as a `baggage.<key>` attribute; the allowed values appear under their real semantic keys (`correlation_id`, `deployment.environment`, …). The `deny_fields` list (SPEC-OP-003) still removes forbidden keys regardless of prefix. |
| CI | `scripts/validate-signal-contracts.py` checks the `.yaml` shape, that forbidden propagators are declared, that every conformant fixture passes and every non-conformant fixture is rejected, and that `transform/baggage-contract` is wired into all three pipelines |
| Runtime | SPEC-OP-035 full-lifecycle E2E trace asserts one trace ID spans Portal → 01–07 → RabbitMQ; the smoke test asserts a publish→consume parent/child link and `baggage.*` stripping |

## 6. Why no dedicated alert

"Propagation is broken" has no clean single-series signal (a root span is normal; a
new trace on consume is legal for fan-out). Conformance is covered by the fixtures in
CI and the SPEC-OP-035 end-to-end lifecycle trace. `BrokenTracePropagation.md` is a
manual-investigation runbook, referenced from the SPEC-OP-016 Golden Path dashboard.

## 7. Schema evolution

Adding an allowed baggage key, or a new (still-W3C) `tracestate` vendor tag → additive,
PR + `platform-observability`. Removing an allowed key, or changing a limit → breaking
per `governance schema_review`; bump this file's `version`.
