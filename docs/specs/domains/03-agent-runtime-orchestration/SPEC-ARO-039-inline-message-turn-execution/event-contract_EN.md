# SPEC-ARO-039 — Event Contract

Goal: support `Inline Message Turn Execution`.

- No new published event. This spec is a synchronous HTTP-request-scoped operation, not an event-driven one.
- The outbound call to `04-memory-knowledge` for knowledge retrieval is a synchronous HTTP/RPC call, not an async event exchange — consistent with this spec's own inline-execution nature.
- If the turn results in an `escalation` response shape, no event is published here; the actual triage call (which does interact with `02-ticket-workflow`'s own real endpoints) belongs to SPEC-ARO-041.
