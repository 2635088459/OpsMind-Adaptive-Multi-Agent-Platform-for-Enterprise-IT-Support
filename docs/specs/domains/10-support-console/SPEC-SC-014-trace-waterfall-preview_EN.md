# SPEC-SC-014 — Trace Waterfall Preview

> Domain: `10-support-console` | Phase: 06 — Observability Surfaces | Status: Implemented

## 1. Spec Identity
`SPEC-SC-014`, implements the OTel half of `UC-SC-07`, matching the "Agent Observability" mockup already approved by the user.

## 2. Objective
Embed a trace-waterfall view (span hierarchy, durations) for a ticket's agent-processing trace, sourced from the real OpenTelemetry backend built in `08-observability-platform` (fully closed, all 36 specs).

## 3. Design References
`01-domain-model` §"TraceWaterfall"; `04-use-cases` UC-SC-07; the Agent Observability mockup (OTel section).

## 4. Actor
A support agent/admin wanting to understand the technical execution path behind an AI-processed ticket.

## 5. Scope
Fetching a trace by ID (correlated via the `traceparent` propagated throughout domains 03/05/09) from the observability platform's query surface, and rendering it as a waterfall (span name, start offset, duration, nesting).

## 6. Non-goals
Building any new tracing infrastructure (domain 08 is complete) — this is purely a console-side query-and-render client of an existing system.

## 7. Preconditions
A ticket has an associated trace ID (available from any span's context propagated during its processing).

## 8. Input
The `traceId`.

## 9. Detailed Behavior
Fetch the trace's span tree from the observability platform's query API and render nested bars proportional to duration, matching the mockup's waterfall visual.

## 10. Interaction State Transition
N/A — a read-only visualization.

## 11. Business Invariants
A new invariant specific to this domain: the waterfall must render the real span data, never a synthetic/illustrative placeholder once wired to production.

## 12. Idempotency Strategy
N/A — a `GET`.

## 13. Consumed/Depended-on Contracts
Not called directly: domain 08's own security doc (`ObservabilityAccessControl.md`, SPEC-OP-030) confirms Tempo/Loki's query APIs were deliberately left with NO authentication (no shared Keycloak existed to gate them against). Rather than point a browser at that surface — a real step up in exposure this platform had never taken before — `user-access-authentication-service` (the existing BFF) gained a new authenticated proxy, `GET /api/v1/observability/traces/{traceId}` (`TraceWaterfallController`/`TraceWaterfallService`/`TempoQueryClient`), confirmed with the user before building (a genuinely consequential, cross-domain-boundary call, not decided unilaterally). This is what this console actually calls.

Tempo's real query API (`GET {tempoBaseUrl}/api/traces/{traceId}`, `X-Scope-OrgID` per SPEC-OP-031) was confirmed LIVE against a running `observability-stack.yml`, not assumed from docs — 2 non-obvious findings that would have silently broken a naive implementation: (a) the response is raw OTLP-JSON (`batches[].resource/scopeSpans[].spans[]`), not the Jaeger-compatible shape first assumed; (b) `traceId`/`spanId`/`parentSpanId` are base64 (proto3 bytes-field convention, not hex) and `startTimeUnixNano`/`endTimeUnixNano` are JSON strings holding a value that exceeds `Number.MAX_SAFE_INTEGER` — both are decoded server-side (Java `long`/`Base64`/`HexFormat`), never handed raw to the browser. Also confirmed live: ADR-0011's real per-producing-domain tenant split — a single ticket-processing trace can legitimately be split across several Tempo tenants; the proxy queries a fixed, real tenant list and merges whatever spans come back, honestly reporting any tenant it could not reach at all (distinct from that tenant simply not touching this trace) via the same outage-vs-absence discipline SPEC-SC-007/019 established.

## 14. Security
Gated to the `support-console` OAuth2 client registration specifically (domain 09's employee-portal session is denied, 403 `TRACE_ACCESS_DENIED`) — same session-cookie authentication as `BrowserSessionTokenController`, no bearer token involved.

## 15. Observability
Meta: this spec is itself an observability-consuming feature; its own fetch also carries a `traceparent` per SPEC-SC-020.

## 16. Error Scenarios
Trace not found under any queried tenant (e.g. outside Tempo's retention window) — a real, clean 404 `TRACE_NOT_FOUND`, rendered as an honest "trace no longer available" state. Distinct from every queried tenant failing to respond at all (Tempo/network unreachable) — a real, retryable 503 `TRACE_QUERY_UNAVAILABLE` — never collapsed into the same generic error.

## 17. Acceptance Scenarios
A trace with nested spans renders a waterfall with correct relative durations and nesting depth — verified against fixtures AND live against the real running stack (a real pushed trace, correctly base64-decoded and offset-computed).

## 18. Tests First
Component tests against fixtures matching the real `TraceWaterfallView` response shape (success, 404, 503, partial-outage); backend unit tests for the Tempo response parsing (real OTLP-JSON fixture, live-confirmed shape), the tenant-merge/outage logic, and the registration gate.

## 19. Definition of Done
The waterfall renders correctly from fixtures for every real outcome (success/404/503/partial-outage); the authenticated proxy backing it is real, tested, and live-verified end-to-end against a running `observability-stack.yml` through a real support-console browser session (not merely fixture-compatible).
