# SPEC-ARO-040 — Event Contract

Goal: support `Confirm/Decline With Bounded Wait`.

- Consumes `tool.completed`/`tool.failed` to resolve the bounded wait — reuses SPEC-ARO-020's existing consumer entirely; this spec adds no new consumer, it only adds a synchronous waiter on top of the same already-consumed events.
- The high-risk branch calls `06-policy-approval-governance`'s real request-approval endpoint synchronously (a direct HTTP call, not an event) — the eventual `approval.granted`/`approval.rejected` events remain SPEC-ARO-021's existing consumption responsibility, unchanged by this spec.
- `decline` publishes no event and makes no outbound call of any kind.
