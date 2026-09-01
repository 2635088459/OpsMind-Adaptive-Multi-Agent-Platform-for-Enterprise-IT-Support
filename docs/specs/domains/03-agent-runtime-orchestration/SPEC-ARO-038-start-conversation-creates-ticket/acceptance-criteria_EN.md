# SPEC-ARO-038 — Acceptance Criteria

Goal: support `Start Conversation Creates Ticket`.

- A real ticket row appears in `02-ticket-workflow`'s own database after calling this endpoint.
- A real `workflow_instances` row appears in `agent-runtime-service`'s own schema, referencing that real `ticketId`.
- Resubmitting the same `Idempotency-Key` never creates a second ticket or a second workflow instance.
- If `02-ticket-workflow` is unavailable, this endpoint fails cleanly with a clear error — it never fabricates a fake `conversationId`.
