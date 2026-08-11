# 03 State Machine

## Workflow State

Agent Workflow state describes runtime orchestration state, not Ticket lifecycle state.

States:

- `CREATED`: instance created, not started.
- `STARTING`: start transaction is preparing planner output and initial tasks.
- `RUNNING`: runnable or running Agent Tasks exist.
- `WAITING_FOR_APPROVAL`: waiting for approval domain event.
- `WAITING_FOR_TOOL`: waiting for Tool Gateway result event.
- `WAITING_FOR_VERIFICATION`: waiting for verification domain event.
- `WAITING_FOR_INPUT`: waiting for user or human input.
- `PAUSING`: pause command accepted and workers are being frozen.
- `PAUSED`: no new task can be claimed; running task results are checked by generation.
- `RESUMING`: resume command accepted and runnable tasks are being restored.
- `COMPLETED`: runtime automation completed.
- `FAILED`: runtime cannot recover automatically.
- `CANCELLED`: ticket cycle was cancelled or workflow was explicitly terminated.

## Workflow Transitions

Common transitions:

- `CREATED -> STARTING -> RUNNING`
- `RUNNING -> WAITING_FOR_TOOL`
- `RUNNING -> WAITING_FOR_APPROVAL`
- `RUNNING -> WAITING_FOR_VERIFICATION`
- `RUNNING -> PAUSING -> PAUSED`
- `PAUSED -> RESUMING -> RUNNING`
- `WAITING_* -> RUNNING`
- `RUNNING -> COMPLETED`
- any non-terminal state `-> FAILED`
- any non-terminal state `-> CANCELLED`

All transitions must record state transition audit and publish required outbox events.

## Agent Task State

States:

- `PENDING`: created and waiting for dependencies.
- `READY`: dependencies are satisfied and task can be claimed.
- `CLAIMED`: worker claimed task but has not started execution.
- `RUNNING`: worker is executing.
- `WAITING_TOOL`: task requested a Tool.
- `WAITING_EXTERNAL`: waiting for approval, verification, or input.
- `COMPLETED`: success.
- `FAILED_RETRYABLE`: retryable failure.
- `FAILED_FINAL`: non-retryable failure.
- `CANCELLED`: stopped by workflow pause/cancel/terminal state.
- `STALE`: claim generation expired or workflow version mismatched.

## Checkpoint Type

- `STARTED`: stable point after workflow start.
- `BEFORE_TASK`: before task execution.
- `AFTER_TASK`: after task completion.
- `BEFORE_TOOL_REQUEST`: before external tool side effect.
- `WAITING_EXTERNAL`: before waiting for an external event.
- `PAUSED`: after entering paused state.
- `RECOVERY`: after crash recovery reconstruction.
- `COMPLETED`: terminal summary.

## External Event Wake-Up

- `approval.granted` wakes `WAITING_FOR_APPROVAL`.
- `tool.completed` wakes `WAITING_FOR_TOOL`.
- `verification.completed` wakes `WAITING_FOR_VERIFICATION`.
- Wake-up must validate correlation id, workflow state, pending request id, and processed-event de-duplication.
