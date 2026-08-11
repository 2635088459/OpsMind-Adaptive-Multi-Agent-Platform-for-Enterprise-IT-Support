# SPEC-ARO-024 — Persistence Design

Goal: support `Duplicate Stale Invalid Event Classification`.

- Tables must stay inside the Agent Runtime boundary.
- Write models must support idempotency, version, or unique-key protection.
- Checkpoint or outbox must be written before external side effects.
- Payloads must be schema-versioned and must not store secrets.
