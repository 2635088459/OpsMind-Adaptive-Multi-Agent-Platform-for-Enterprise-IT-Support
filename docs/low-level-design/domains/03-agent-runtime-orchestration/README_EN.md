# Agent Runtime Orchestration LLD

## Scope

This directory defines the low-level design for `03-agent-runtime-orchestration`. The domain turns business events emitted by Ticket Workflow into recoverable, pausable, auditable agent execution.

Agent Runtime does not own the ticket lifecycle state. Ticket state remains owned by `02-ticket-workflow`. This domain owns only Agent Workflow state: where automation is, what it is waiting for, which Agent Tasks are complete, and how the runtime recovers after a crash.

## Core Answers

- A Workflow Instance is one automation orchestration instance started for a ticket/cycle. It is not the Ticket and does not own Ticket state.
- An Agent Task is the smallest schedulable unit assigned to an agent role inside a Workflow Instance.
- A Checkpoint is stored as structured JSON payload plus version, cursor, and checksum, written before and after external side effects.
- Pause / Resume is idempotent through command idempotency keys, workflow version, pause generation, and outbox de-duplication.
- Multiple agents are orchestrated through a planner, task queue, dependency graph, claim lease, join policy, and coordinator.
- After a runtime crash, recovery uses workflow state, checkpoints, pending tasks, event cursors, outbox replay, and lease expiry.
- Runtime consumes `ticket.created`, `approval.granted`, `tool.completed`, `verification.completed`, and related events through a processed-event de-duplication table.
- Runtime publishes `workflow.started`, `workflow.paused`, `agent.task.completed`, and related domain events through its own outbox.
- Agents cannot call Tools directly. Every tool call must create a Tool Request and go through Tool Gateway.
- Agent Workflow state and Ticket state stay separate. They are linked by ticket id/cycle id and synchronized by events, not by direct state mutation.

## 14 LLD Sections

1. `01-domain-model`: Workflow Instance, Agent Task, Checkpoint, Tool Request, Event Cursor.
2. `02-business-invariants`: cross-entity invariants, Tool Gateway boundary, state separation.
3. `03-state-machine`: Workflow, Task, and Checkpoint state machines.
4. `04-use-cases`: event-driven start, pause, resume, multi-agent orchestration, completion.
5. `05-api-contracts`: runtime internal APIs, admin APIs, agent callbacks.
6. `06-event-contracts`: consumed and published event contracts.
7. `07-data-model`: tables, indexes, unique keys, retention.
8. `08-transaction-and-outbox`: transaction boundaries, outbox, checkpoint order.
9. `09-concurrency-and-idempotency`: concurrent claims, idempotent commands, duplicate events.
10. `10-failure-handling`: crash recovery, retries, poison events, compensation.
11. `11-security`: agent permissions, Tool Gateway, credentials, audit.
12. `12-observability`: logs, metrics, traces, audit events.
13. `13-package-and-class-design`: packages, services, ports, adapters.
14. `14-testing-strategy`: unit, integration, contract, recovery, and chaos tests.

## Relationship With Ticket Workflow

Ticket Workflow decides whether the business process is valid. Agent Runtime Orchestration decides how automation work is executed. The domains communicate through events and read-only queries. They do not share state machines or transaction boundaries, and Agent Runtime must not directly advance Ticket state.
