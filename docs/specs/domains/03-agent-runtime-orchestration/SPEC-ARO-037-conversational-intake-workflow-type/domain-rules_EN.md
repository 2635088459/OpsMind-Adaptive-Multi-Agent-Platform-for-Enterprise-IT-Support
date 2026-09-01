# SPEC-ARO-037 — Domain Rules

Goal: support `Conversational Intake Workflow Type`.

- `workflow_type="conversational_intake"` reuses `WorkflowInstance`'s existing version/state invariants unchanged.
- `task_type="process_user_message"`/`"execute_confirmed_action"` are subject to the exact same claim/version rules as every pre-existing `task_type`.
- This spec introduces no new `WorkflowState`/`AgentTaskState` value on its own (the new `AWAITING_USER_CONFIRMATION` task state belongs to SPEC-ARO-040).
