# SPEC-SC-014 — Trace Waterfall Preview

> Domain: `10-support-console` | Phase: 06 — Observability Surfaces | Status: Spec Planning

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
Domain 08's observability-platform query API (its exact query surface — e.g., a Tempo/Jaeger-compatible API — to be confirmed against domain 08's own API contract doc before wiring).

## 14. Security
Requires whatever read scope domain 08's query surface defines for trace access (to be confirmed against domain 08's own security section).

## 15. Observability
Meta: this spec is itself an observability-consuming feature; its own fetch also carries a `traceparent` per SPEC-SC-020.

## 16. Error Scenarios
Trace not found (e.g., outside the observability backend's retention window) — an honest "trace no longer available" state, not a blank/broken view.

## 17. Acceptance Scenarios
A trace with 5 nested spans renders a waterfall with correct relative durations and nesting depth.

## 18. Tests First
A component test against a fixture matching domain 08's real span-tree response shape.

## 19. Definition of Done
The waterfall renders correctly from fixtures; a compatibility check against the real query API is added once domain 08's exact endpoint is confirmed.
