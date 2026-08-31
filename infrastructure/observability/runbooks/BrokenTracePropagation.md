# BrokenTracePropagation

> owner: platform-observability
> version: 0.1.0
> spec: SPEC-OP-005
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: n/a (investigation runbook — no config to roll back)
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-005-traceability.md

Manual-investigation runbook. There is **no dedicated alert** (SPEC-OP-005 §6): a root
span is normal and a new trace on consume is legal for fan-out, so "propagation is
broken" has no clean single-series signal. This runbook is linked from the SPEC-OP-016
Golden Path dashboard and used when a trace that should be one request appears as
several disconnected traces.

## Impact

**Observability only.** The business request still works; it is just not traceable
end to end, so latency attribution, error correlation, and the SPEC-OP-035 lifecycle
trace are degraded for the affected path.

## Symptoms

- A ticket / request that crosses services shows up as 2+ traces in Tempo instead of
  one.
- A RabbitMQ consumer span is always a trace root (never a child of the publisher).
- `correlation_id` is present on the first service's spans but missing downstream.
- B3 / Jaeger / Datadog headers seen on inbound requests (check an access log or a
  `tcpdump` on a dev box).

## Triage

1. Identify the two services where the trace breaks (last span of trace A, first span
   of trace B, close in time, same `correlation_id`).
2. **HTTP break**: on the *calling* service, confirm `OTEL_PROPAGATORS=tracecontext,baggage`
   and that its HTTP client is instrumented. On the *called* service, confirm the
   server framework is instrumented and a reverse proxy in between is not stripping
   `traceparent` (`signals/trace-propagation.md` §2).
3. **AMQP break**: confirm the publisher injects `traceparent` into the **message
   header table** and the consumer extracts from it before creating the CONSUMER span
   (`signals/trace-propagation.md` §3). Check a message in the RabbitMQ management UI
   for a `traceparent` header.
4. **Wrong propagator**: if inbound requests carry `x-b3-*` / `uber-trace-id` /
   `x-datadog-*`, a service has an extra propagator configured — remove it, keep only
   `tracecontext,baggage`.
5. **Baggage dropped**: baggage does not survive a hop where the propagator list omits
   `baggage`, or where an allow-listed key was renamed. Check both ends.

## Mitigation

Producer-side fix in the owning domain(s): set `OTEL_PROPAGATORS`, instrument the
missing client/server, fix the AMQP header carrier, or remove the stray propagator.
The observability side has nothing to change.

## Verification

Re-run the flow; confirm a single trace in Tempo spanning both services, the CONSUMER
span as a child of the PRODUCER span, and `correlation_id` on spans in every service.
The fixtures in `signals/fixtures/trace-propagation/` are the reference for a
conformant carrier.

## Escalation

`platform-observability` opens a ticket against the owning domain team(s). No paging.

## Post-incident

If multiple services share the defect, fix it in the shared SDK bootstrap / base image
and record it in the SPEC-OP-025+ cross-domain observability contract for those
domains.
