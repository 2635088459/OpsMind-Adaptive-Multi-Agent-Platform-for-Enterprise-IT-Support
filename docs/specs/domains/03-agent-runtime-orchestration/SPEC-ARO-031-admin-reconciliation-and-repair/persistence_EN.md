# SPEC-ARO-031 — Persistence Design

Goal: support `Admin Reconciliation and Repair`.

- Tables must stay inside the Agent Runtime boundary.
- Write models must support idempotency, version, or unique-key protection.
- Checkpoint or outbox must be written before external side effects.
- Payloads must be schema-versioned and must not store secrets.
