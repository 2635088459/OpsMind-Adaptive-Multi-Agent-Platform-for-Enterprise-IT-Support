# SPEC-ARO-041 — Persistence Design

Goal: support `Escalation Via Existing Triage`.

- No new table. The `WorkflowInstance` transitions to an existing terminal state (e.g. `COMPLETED`), with the escalation reason recorded in its own existing fields — no new column.
- The ticket's own triage-related fields (category, subcategory, support queue) are written by `02-ticket-workflow` in its own schema, via its own real endpoint.
