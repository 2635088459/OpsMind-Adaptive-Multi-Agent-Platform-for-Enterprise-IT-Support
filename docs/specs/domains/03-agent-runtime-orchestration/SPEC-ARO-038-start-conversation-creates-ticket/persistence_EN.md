# SPEC-ARO-038 — Persistence Design

Goal: support `Start Conversation Creates Ticket`.

- No new table on the `agent-runtime-service` side — a normal new `workflow_instances` row (`workflow_type="conversational_intake"`).
- The real ticket row is written by `02-ticket-workflow` in its own schema, via its own real endpoint — never written directly by this service.
- The `Idempotency-Key` reuses the existing idempotency mechanism already backing other `agent-runtime-service` commands.
