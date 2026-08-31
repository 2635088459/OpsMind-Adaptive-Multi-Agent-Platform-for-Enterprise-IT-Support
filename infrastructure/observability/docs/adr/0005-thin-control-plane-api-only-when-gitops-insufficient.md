# ADR-0005: A thin control-plane API is added only where GitOps cannot express a change safely

> Status: Accepted
> Date: 2026-08-30
> Spec: SPEC-OP-001
> Deciders: platform-observability

## Context

Most observability configuration is best managed as reviewed files (ADR-0003). A few
operations are time-sensitive or inherently stateful and awkward to drive purely
through Git: creating a short-lived silence during an incident, adjusting a retention
window under storage pressure, toggling an SLO's enforcement, acknowledging an
error-budget burn.

## Decision

No general-purpose observability business service is created. A **thin control-plane
API** may be introduced later — and only for operations that GitOps cannot express
safely or quickly enough:

- audited silence create / expire,
- retention window adjustment within policy bounds,
- SLO enable / disable and error-budget annotation,
- alert-rule enable / disable (not authoring).

When it is introduced (its own spec, gated by `SPEC-OP-030` / `SPEC-OP-032`), it must:

1. authenticate via domain-01 identity with a scoped role;
2. require domain-06 approval for high-risk actions (retention reduction, deletion,
   critical-alert-class silence);
3. accept correlation and idempotency keys;
4. write an immutable audit record for every mutation;
5. return outcomes that distinguish validation / authorization / conflict / dependency
   unavailable / rollback failure;
6. never touch business state.

Rule/dashboard **authoring** stays in Git regardless.

## Consequences

- The default remains "change a file, open a PR". The API is an exception surface, not
  a convenience layer.
- Until a concrete need is proven, this API does not exist — `SPEC-OP-001` ships zero
  API code.
- Audit and approval integration is a hard prerequisite, so the API cannot be a
  shortcut around governance.

## Alternatives considered

- **Everything through GitOps, no API ever.** Rejected: incident-time silences and
  storage-pressure retention cuts need a faster, stateful path with an audit trail.
- **Full observability admin service now.** Rejected: over-engineering; most of its
  surface duplicates Git-managed config and adds a business-shaped service the domain
  explicitly avoids.
