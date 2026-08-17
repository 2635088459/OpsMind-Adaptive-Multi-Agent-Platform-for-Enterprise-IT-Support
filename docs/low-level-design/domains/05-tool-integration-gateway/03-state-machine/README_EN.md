# 03 State Machine

## Tool Request State Machine

```text
RECEIVED
  -> VALIDATING
  -> POLICY_CHECKING
  -> WAITING_APPROVAL
  -> APPROVED
  -> QUEUED
  -> EXECUTING
  -> COMPLETED

RECEIVED -> REJECTED
VALIDATING -> REJECTED
POLICY_CHECKING -> POLICY_DENIED
WAITING_APPROVAL -> APPROVAL_DENIED
QUEUED -> CANCELLED
EXECUTING -> CANCEL_REQUESTED
EXECUTING -> COMPLETED
EXECUTING -> FAILED
FAILED -> QUEUED
FAILED -> TERMINAL_FAILED
```

## Tool Request State Semantics

- `RECEIVED`: Gateway received the request but has not completed validation persistence.
- `VALIDATING`: validating idempotency, schema, capability, actor, and ticket/workflow references.
- `POLICY_CHECKING`: waiting for or performing policy/risk decision.
- `WAITING_APPROVAL`: human or governance approval is required.
- `APPROVED`: approval granted or low-risk auto approval applied.
- `QUEUED`: executable and waiting for worker claim.
- `EXECUTING`: an active ToolExecution attempt exists.
- `COMPLETED`: an execution attempt ended with final result and result event was published.
- `FAILED`: retryable failure.
- `TERMINAL_FAILED`: non-retryable failure.
- `POLICY_DENIED`: policy denied execution.
- `APPROVAL_DENIED`: approval denied execution.
- `CANCEL_REQUESTED`: cancellation was recorded while an active connector may still be running.
- `CANCELLED`: safely cancelled before or during execution.
- `REJECTED`: invalid request; never enters execution.

## Execution Attempt State Machine

```text
CREATED
  -> CLAIMED
  -> PREPARING
  -> INVOKING
  -> NORMALIZING_RESULT
  -> COMPLETED

CLAIMED -> LEASE_EXPIRED
PREPARING -> FAILED
INVOKING -> TIMED_OUT
INVOKING -> FAILED
INVOKING -> PARTIAL_SIDE_EFFECT
NORMALIZING_RESULT -> FAILED
FAILED -> RETRY_SCHEDULED
TIMED_OUT -> RECONCILING
PARTIAL_SIDE_EFFECT -> RECONCILING
RECONCILING -> COMPLETED
RECONCILING -> TERMINAL_FAILED
```

## Approval Linkage State Machine

```text
NOT_REQUIRED
REQUIRED -> APPROVAL_REQUESTED -> APPROVED
REQUIRED -> APPROVAL_REQUESTED -> DENIED
APPROVAL_REQUESTED -> EXPIRED
APPROVAL_REQUESTED -> CANCELLED
```

Gateway stores only approval linkage and decision snapshots. Approval rules, approvers, approval SLA, and approval history are owned by `06-policy-approval-governance`.

## Connector Health State Machine

```text
ACTIVE -> DEGRADED -> ACTIVE
ACTIVE -> DISABLED
DEGRADED -> DISABLED
DISABLED -> ACTIVE
ACTIVE -> DEPRECATED
DEPRECATED -> DISABLED
```

Scheduling may select only `ACTIVE` connectors. A `DEGRADED` connector is allowed only for read-only or low-risk fallback unless policy explicitly permits otherwise.

## State Separation

`ToolRequest.COMPLETED` does not advance:

- `Ticket.RESOLVED`
- `Ticket.CLOSED`
- `Workflow.COMPLETED`
- `AgentTask.COMPLETED`

Gateway publishes `tool.completed.v1`; Runtime consumes it and decides whether the agent task is complete. Ticket Workflow then decides ticket transitions based on workflow/tool/verification facts.

