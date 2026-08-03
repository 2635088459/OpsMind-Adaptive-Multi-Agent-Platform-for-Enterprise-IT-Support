# Phase 05 — Policy and Approval Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 05
>
> Specs: `SPEC-TW-014` through `SPEC-TW-018`
>
> Prerequisites: Phase 01 through Phase 04 implemented and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 05 provides the safety precondition for later Tool Execution: Ticket Workflow allows the executable path only after policy allows the action or approval is granted.

The earlier roadmap uses:

```text
INVESTIGATING -> WAITING_FOR_APPROVAL -> EXECUTING
REJECTED / EXPIRED -> INVESTIGATING
```

The current Phase 03/04 persistence model is:

```text
IN_PROGRESS -> WAITING_FOR_APPROVAL -> IN_PROGRESS
```

Therefore Phase 05 stores pending action and approval references, handles trusted approval-domain events, and returns the ticket to `IN_PROGRESS` so Phase 06 can continue through dedicated Tool Execution commands. Phase 05 does not execute tools.

## 2. Business Value

High-risk IT actions cannot be decided by Tool Gateway or Agent alone. Approval is bound to ticket, workflow, action, and risk context, and one approval authorizes exactly one action.

Phase 05 provides:

- request approval;
- approval granted/rejected/expired event handling;
- auto-approved policy decision;
- stale/wrong-producer/duplicate event classification;
- approval is not reusable;
- expired approval cannot execute;
- timeline, audit, status history, outbox, and idempotency.

## 3. Scope

### 3.1 Included

- `SPEC-TW-014-request-approval`
- `SPEC-TW-015-apply-approval-granted`
- `SPEC-TW-016-apply-approval-rejected`
- `SPEC-TW-017-apply-approval-expired`
- `SPEC-TW-018-apply-auto-approved-policy`
- `IN_PROGRESS -> WAITING_FOR_APPROVAL`
- `WAITING_FOR_APPROVAL -> IN_PROGRESS`
- pending action reference;
- approval reference;
- policy decision reference;
- event consumer validation;
- DLQ classification;
- transactional outbox.

### 3.2 Excluded

- real Approval Service implementation;
- approval UI;
- tool execution;
- verification;
- notification delivery;
- approval policy editor;
- advanced risk model training.

## 4. Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-014` | Request Approval | Request approval for a pending action and wait |
| 2 | `SPEC-TW-015` | Apply Approval Granted | Consume approval granted and store executable authorization |
| 3 | `SPEC-TW-016` | Apply Approval Rejected | Consume approval rejected and resume work |
| 4 | `SPEC-TW-017` | Apply Approval Expired | Consume approval expired and resume work |
| 5 | `SPEC-TW-018` | Apply Auto-Approved Policy | Apply low-risk auto-approved policy |

## 5. State Transitions

| Current | Target | Trigger |
|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | Request Approval |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Approval Granted |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Approval Rejected |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Approval Expired |
| `IN_PROGRESS` | `IN_PROGRESS` | Auto-Approved Policy |

Approval granted does not directly enter Tool Execution; it records authorization and releases the waiting state. Phase 06 executes tools.

## 6. Events

Ticket Workflow publishes:

```text
ticket.approval-wait-started.v1
ticket.approval-granted-applied.v1
ticket.approval-rejected-applied.v1
ticket.approval-expired-applied.v1
ticket.auto-approval-applied.v1
```

Ticket Workflow consumes:

```text
approval.granted.v1
approval.rejected.v1
approval.expired.v1
policy.action-auto-approved.v1
```

## 7. Exit Criteria

- all Phase 05 specs docs, code, migrations, contracts, and tests are complete;
- approval binds ticket, workflow, action, and risk context;
- expired/rejected approval cannot authorize execution;
- duplicate granted/rejected/expired events are idempotent;
- wrong producer or schema-invalid event goes to DLQ;
- stale event does not advance ticket;
- Phase 06 can continue Tool Execution from the stored authorization reference.
