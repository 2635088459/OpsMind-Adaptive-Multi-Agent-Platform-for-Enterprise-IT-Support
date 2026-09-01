# SPEC-ARO-037 — Persistence Design

Goal: support `Conversational Intake Workflow Type`.

- No new table. Reuses `workflow_instances`/`agent_tasks` as-is.
- The migration only widens the allowed-value set of the existing `workflow_type`/`task_type` columns (CHECK constraint or enum type, matching whichever mechanism the original columns already use) — it does not add, rename, or drop a column.
- No payload/schema-version change to `checkpoints` or `tool_requests`.
