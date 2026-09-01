# SPEC-ARO-040 — Persistence Design

Goal: support `Confirm/Decline With Bounded Wait`.

- No new table. Reuses existing `agent_tasks` (new `AWAITING_USER_CONFIRMATION` state value), `tool_requests`, and `checkpoints`.
- The real `approval_requests` row for the high-risk branch is written by `06-policy-approval-governance` in its own schema, via its own real endpoint — never written directly by this service.
- `decline` writes no new row anywhere beyond the task's own terminal-state transition.
