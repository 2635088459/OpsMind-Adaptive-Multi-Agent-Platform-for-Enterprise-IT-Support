# SPEC-ARO-037 — Acceptance Criteria

Goal: support `Conversational Intake Workflow Type`.

- A `WorkflowInstance` can be created with `workflow_type="conversational_intake"` and passes existing persistence validation.
- No existing `workflow_type`'s behavior, state transitions, or tests change.
- The fixed `task_graph` template resolves deterministically for this workflow type without a caller-supplied graph.
- The full existing test suite for `agent-runtime-service` remains green after the migration.
