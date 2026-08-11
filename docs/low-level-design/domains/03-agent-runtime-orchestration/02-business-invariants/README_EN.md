# 02 Business Invariants

## State Ownership

- Ticket state belongs only to Ticket Workflow.
- Agent Workflow state belongs only to Agent Runtime.
- Runtime may read Ticket snapshots but must not directly write Ticket lifecycle state.
- If Runtime needs Ticket state to advance, it must emit an explicit command or event and let Ticket Workflow validate legality.

## Workflow Instance Invariants

- At most one active Workflow Instance may exist for the same `ticketId + ticketCycleId + workflowType`.
- A Workflow Instance must bind to `definitionVersion`; recovery must not silently switch definitions.
- Every state transition increments `workflowVersion`.
- After a terminal state, no new Agent Task may be created unless a new ticket cycle creates a new Workflow Instance.

## Agent Task Invariants

- An Agent Task must belong to one Workflow Instance.
- An Agent Task cannot be reused across Workflow Instances.
- All `dependsOn` tasks must complete before execution.
- Task completion must write either a result payload or an explicit failure reason.
- Task completion event may be published once.

## Tool Gateway Boundary

Agents cannot call Tools directly because:

- Runtime must centralize authorization, audit, rate limiting, and retry.
- Runtime must write checkpoints before external side effects.
- Tool results must return through `tool.completed` or `tool.failed`.
- Ticket Workflow needs controlled and traceable tool side effects.

Therefore Agent SDKs, Agent Workers, and Task Handlers must not hold Tool clients. They may only use a request interface represented by `ToolGatewayPort`, implemented in the Runtime adapter layer.

## Pause / Resume Idempotency Invariants

- Pause command must include `idempotencyKey`.
- Resume command must include `idempotencyKey`.
- Duplicate pause returns the same paused result and must not publish `workflow.paused` twice.
- Duplicate resume returns the same resumed result and must not claim tasks twice.
- `pauseGeneration` increments on every successful pause, and task workers must validate it when submitting results.

## Checkpoint Invariants

- A checkpoint must exist before any external side effect.
- Every recoverable waiting state must include a checkpoint.
- Checkpoint payload must be parsed by schema version.
- Checkpoint must not store secrets.

## Multi-Agent Orchestration Invariants

- Planner produces a task graph and never executes tools directly.
- Coordinator decides which tasks are runnable.
- Worker claim must use a lease; after lease expiry the task can be claimed again.
- Join policy must be explicit: all-success, first-success, quorum, or manual-review.

## Event Handling Invariants

- Every consumed event must be checked against or written to `processed_events` in the same transaction.
- Every published event must go through outbox. Broker messages must not be sent synchronously inside business transactions.
- Every published event must include `workflowInstanceId`, `ticketId`, `correlationId`, and `causationId`.
