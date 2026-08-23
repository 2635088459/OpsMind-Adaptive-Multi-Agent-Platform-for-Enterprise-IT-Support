# SPEC-PG-002 — Policy Governance Schema Baseline

> Domain: Policy Approval Governance
>
> Phase: 00 — Engineering Foundation
>
> Service: `policy-approval-governance-service`
>
> LLD Mapping: `07-data-model, 03-state-machine`
>
> Document Status: Spec Planning

## 1. Goal

Create database baseline for policies, policy_versions, policy_decisions, approval_requests, approval_decisions, audit, outbox, and processed_events.

## 2. Scope

Includes:

- domain/application/infrastructure/interface design required by this spec;
- corresponding persistence, API/event contract, tests, and acceptance criteria;
- consistency with Policy Approval Governance LLD boundaries.

Excludes:

- direct Tool execution; direct Ticket/Workflow state mutation; Memory content writes; forged approval; bypassing separation of duties; cross-domain distributed transactions.

## 3. Core Rules

- 06 may only output governance facts; decisions must bind policy version/input hash/reason codes/constraints; approval final decisions must be idempotent and unique; every governance state transition must write audit/outbox in the same transaction.
- This spec must not make 06 own Ticket, Workflow, Tool Execution, or Memory state.
- Facts produced by this spec must be traceable to source domain, source request, actor, policy version, and correlation id.
