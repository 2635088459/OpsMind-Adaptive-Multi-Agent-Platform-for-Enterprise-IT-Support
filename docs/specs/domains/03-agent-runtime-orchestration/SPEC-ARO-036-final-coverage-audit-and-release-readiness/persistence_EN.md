# SPEC-ARO-036 — Persistence Design

Goal: support `Final Coverage Audit and Release Readiness`.

- Tables must stay inside the Agent Runtime boundary.
- Write models must support idempotency, version, or unique-key protection.
- Checkpoint or outbox must be written before external side effects.
- Payloads must be schema-versioned and must not store secrets.
