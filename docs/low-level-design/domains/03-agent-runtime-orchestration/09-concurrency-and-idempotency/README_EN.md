# 09 Concurrency and Idempotency

## Concurrency Model

Runtime may scale horizontally with multiple workers. Concurrency safety must be guaranteed by database locks, optimistic versions, unique keys, and idempotency tables.

## Task Claim

Claim rules:

- Only `READY` tasks can be claimed.
- Workflow must be in `RUNNING`.
- `pauseGeneration` must be copied into the task claim.
- Use `FOR UPDATE SKIP LOCKED` or an equivalent mechanism to prevent multiple workers from claiming the same task.
- After claim succeeds, write `claimToken` and `claimExpiresAt`.

Worker completion must submit `claimToken`. Mismatch is rejected.

## Workflow Version

Every workflow state change increments `workflowVersion`.

Task worker receives `workflowVersion` when reading a task and must validate it on result submission, or validate an allowed version range. For pause/resume, it must also validate `pauseGeneration`.

## Consumed Event Idempotency

Each consumer checks before processing:

- `eventId`
- `consumerName`
- `eventType`

Duplicate events must return the already processed result and must not recreate tasks, tool requests, or outbox events.

## Command Idempotency

Start, Pause, Resume, Complete Task, and Request Tool must include `idempotencyKey`.

Idempotency record stores:

- request hash
- response payload
- command status
- target id

Same key with different request hash must return conflict.

## How Pause / Resume Is Idempotent

Pause:

- First successful pause writes `command_idempotency`.
- Duplicate pause returns saved response directly.
- If workflow is already `PAUSED` without the same idempotency key, return current paused state and do not publish another event.

Resume:

- First successful resume writes `command_idempotency`.
- Duplicate resume returns saved response directly.
- If workflow already resumed, use idempotency key to decide saved response or conflict.

## External Callback Idempotency

`tool.completed`, `approval.granted`, and `verification.completed` must validate:

- event id has not been processed.
- pending request id matches.
- workflow is in the corresponding waiting state.
- request state is not already completed.

If any condition fails, Workflow must not advance again.
