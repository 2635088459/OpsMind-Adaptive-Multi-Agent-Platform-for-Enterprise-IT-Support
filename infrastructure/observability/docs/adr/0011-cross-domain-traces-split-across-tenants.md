# ADR-0011: A real cross-domain trace splits across Tempo tenants under SPEC-OP-031's model — the correlation entry point is per-tenant, not a single omniscient query

> Status: Accepted
> Date: 2026-09-01
> Spec: SPEC-OP-035
> Deciders: platform-observability

## Context

`SPEC-OP-035`'s objective is to "run full Identity/MFA trace across domains
and transports with dashboard/alert/runbook/chaos verification." Building
the first genuinely cross-domain trace this domain has ever pushed (5
spans: `user-access-authentication-service`'s login + MFA step-up + an AMQP
publish, consumed by `ticket-workflow-service` which then resolves a
ticket — all one real `traceId`) surfaced a real, previously-unnoticed
consequence of `SPEC-OP-031`'s own real, already-shipped per-producing-
domain tenant isolation:

**A single trace whose spans come from more than one producing domain gets
split across more than one Tempo tenant.** The routing connector routes
per-span-batch on `service.namespace` (`SPEC-OP-031`), not per-trace —
spans 1-3 (`service.namespace=user-access-authentication`) landed in the
`user-access-authentication` tenant; spans 4-5
(`service.namespace=ticket-workflow`) landed in the `ticket-workflow`
tenant. Confirmed directly: querying `GET /api/traces/{id}` under only the
`user-access-authentication` tenant returned exactly 3 spans; the other 2
were found, intact, under the `ticket-workflow` tenant. **Neither query was
wrong or broken** — this is exactly what per-domain tenant isolation is
supposed to do (keep each domain's data in its own isolated store) — it
just means "the full trace" is no longer a single query's result.

OSS Tempo (the pinned `2.7.1`, no Enterprise/GEM features) has no
cross-tenant query capability at all — there is no way to ask "show me
every span for trace X regardless of tenant" in one request.

## Decision

- **This is accepted as a real, direct, foreseeable consequence of the
  per-producing-domain tenant model this domain already chose (with the
  user, across 3 rounds) in `SPEC-OP-031`** — not a new problem to solve
  by re-architecting tenant isolation. Reversing or complicating that
  already-shipped, already-verified decision now, to make cross-domain
  traces single-query-correlatable, is out of scope for this spec.
- **The real "correlation entry point" for a cross-domain trace under this
  domain's real tenant model is: the same `trace_id`, looked up under EACH
  tenant the trace is expected to touch** (known from which domains
  participate in the business flow being investigated), not a single
  omniscient query. This is how `SPEC-OP-035`'s own chaos-e2e drill
  verifies a cross-domain trace (query both `user-access-authentication`
  and `ticket-workflow` tenants, confirm each holds its own real subset of
  spans) and is the documented procedure for a human investigating a real
  incident that spans domains.
- **`SPEC-OP-031`'s own traceability doc is updated** with a pointer to
  this finding, since it is a materially clarifying consequence of that
  spec's decision that its own author (this session) had not fully
  worked through at the time.

## Consequences

- Grafana's Explore correlation view (metrics ↔ logs ↔ traces) is
  correspondingly tenant-scoped for traces — a human correlating a
  cross-domain incident must switch tenant context (or run parallel
  queries) rather than see one merged timeline. Stated honestly as a real
  operational cost of the tenant model, not hidden.
- A future spec wanting single-query cross-domain trace correlation would
  need either Tempo Enterprise/GEM (a real product with multi-tenant
  federation features) or a customer-tenant model instead of a
  per-producing-domain one (the exact alternative `SPEC-OP-031` explicitly
  considered and the user chose not to take, given the real semantic
  mismatch found in deriving customer-tenant.id from domain 01's
  role-assignment model).
- This is unrelated to and does not affect Loki (log correlation): a log
  line's `trace_id` field is a plain string attribute, not something Loki
  itself enforces tenant-consistency on the way Tempo's trace storage
  does — a human can still grep logs across tenants they have access to
  by `trace_id` value, same practical workaround.

## Alternatives considered

- **Route an entire trace to one tenant based on its originating span.**
  Rejected: the routing connector operates on individual resource-batches,
  not whole traces — it cannot see "what other spans share this trace ID"
  at routing time (the same resource-batch-context limitation
  `SPEC-OP-009` already discovered and documented when its own first
  routing-connector attempt failed for an unrelated reason). Building
  trace-ID-aware stateful routing would be a large, new mechanism, not a
  small fix, and is out of this spec's scope.
- **Silently pick one tenant to query and only show a partial trace.**
  Rejected: this spec's own drill caught exactly this mistake on the first
  attempt (querying only `user-access-authentication` reported 2 spans
  "missing" that had, in fact, landed correctly under the other tenant) —
  documenting the real per-tenant correlation procedure is more honest and
  more useful than silently omitting the other domain's spans.
