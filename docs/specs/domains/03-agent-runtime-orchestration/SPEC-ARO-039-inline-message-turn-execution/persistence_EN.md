# SPEC-ARO-039 — Persistence Design

Goal: support `Inline Message Turn Execution`.

- No new table. A new `agent_tasks` row (`task_type="process_user_message"`) and a new `checkpoints` row per turn, using existing schemas.
- The message text/attachment references are stored in the task's existing `inputPayload` field, schema-versioned as `01-domain-model` already requires — no new column.
- No plaintext secrets or raw tool credentials are ever written into the checkpoint payload, matching the existing checkpoint invariant.
