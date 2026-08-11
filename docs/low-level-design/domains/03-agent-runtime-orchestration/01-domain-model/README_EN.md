# 01 Domain Model

## Goal

Define the core entities of Agent Runtime Orchestration. The model must support multi-agent orchestration, pause/resume, crash recovery, Tool Gateway calls, external callbacks, and auditability.

## What Is a Workflow Instance

A Workflow Instance is one runtime orchestration instance created for an automation cycle of a Ticket.

It contains:

- `workflowInstanceId`: runtime-owned primary key.
- `ticketId`: related Ticket id, without owning Ticket state.
- `ticketCycleId`: separates reopen/reassign execution cycles.
- `workflowType`: for example `ticket_triage`, `approval_followup`, `remediation`, `verification_followup`.
- `state`: Agent Workflow state owned by Runtime.
- `definitionVersion`: orchestration definition version.
- `workflowVersion`: optimistic locking version.
- `pauseGeneration`: pause/resume generation.
- `currentCheckpointId`: latest stable checkpoint.

A Workflow Instance is not the Ticket. It cannot move a Ticket from OPEN to IN_PROGRESS or close a Ticket directly. It may only publish Runtime events or request Ticket Workflow to advance through an explicit command/request boundary.

## What Is an Agent Task

An Agent Task is the smallest schedulable work unit inside a Workflow Instance.

It contains:

- `agentTaskId`: task primary key.
- `workflowInstanceId`: parent Workflow Instance.
- `agentRole`: execution role, such as `triage_agent`, `kb_agent`, `remediation_agent`, `verification_agent`.
- `taskType`: such as `classify`, `collect_context`, `propose_action`, `request_tool`, `evaluate_result`.
- `state`: task-owned state.
- `dependsOn`: dependency set.
- `claimOwner` and `claimExpiresAt`: concurrent worker claim control.
- `inputPayload` and `resultPayload`: structured input/output.
- `attempt` and `maxAttempts`: retry control.

Agent Task is an internal runtime concept and must not be exposed as a Ticket sub-state.

## How Checkpoints Are Stored

A Checkpoint is a stable snapshot used to recover runtime execution.

Minimum fields:

- `checkpointId`
- `workflowInstanceId`
- `workflowVersion`
- `checkpointType`
- `cursor`
- `payloadJson`
- `payloadSchemaVersion`
- `checksum`
- `createdAt`

`payloadJson` must be structured JSON containing recoverable context: planner output, completed tasks, pending tool request, pending approval id, verification request id, and summarized agent scratchpad.

Checkpoint must not store plaintext secrets, one-time tokens, Tool Gateway credentials, or unauditable private agent state.

## Tool Request

Agents cannot call Tools directly. An Agent may only submit a Tool Request to Runtime. Runtime persists it and invokes Tool Gateway.

Tool Request contains:

- `toolRequestId`
- `workflowInstanceId`
- `agentTaskId`
- `toolName`
- `capability`
- `inputPayload`
- `policySnapshot`
- `gatewayCorrelationId`
- `state`

When the tool finishes, Runtime consumes `tool.completed` or `tool.failed` and resumes the matching Workflow.

## Event Cursor

Event Cursor records which external event or internal step Runtime has processed. It prevents duplicate execution and step skipping after crash recovery.

Cursor is not the only source of truth for broker offsets. Final idempotency depends on the `processed_events` table and business unique keys.
