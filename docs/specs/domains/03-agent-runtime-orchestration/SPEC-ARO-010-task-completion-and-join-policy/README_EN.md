# SPEC-ARO-010 — Task Completion and Join Policy

> Domain: Agent Runtime Orchestration
>
> Phase: 02 — Agent Task Orchestration
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `02-business-invariants`, `08-transaction-and-outbox`
>
> Document Status: Spec Planning

## 1. Goal

Implement `Task Completion and Join Policy` while Runtime owns only Agent Workflow state and never directly mutates Ticket state.

## 2. Scope

Includes:

- domain/application/infrastructure/interface design required by this spec;
- related persistence, API/event contract, tests, and acceptance criteria;
- consistency with mapped sections under `docs/low-level-design/domains/03-agent-runtime-orchestration`.

Excludes:

- redesigning the Ticket Workflow state machine;
- direct Tool calls from Agents;
- cross-domain distributed transactions;
- capabilities reserved for later phases.

## 3. Core Rules

- every write operation must have idempotency or version protection;
- every published event must go through Runtime outbox;
- every consumed event must use processed-event de-duplication;
- every tool side effect must go through Tool Gateway;
- crash recovery must be derivable from checkpoint, cursor, lease, or outbox.
