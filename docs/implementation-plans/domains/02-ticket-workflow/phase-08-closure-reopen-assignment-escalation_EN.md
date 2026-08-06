# Phase 08 — Closure, Reopen, Assignment, and Escalation Slice

> Domain: Ticket Workflow
>
> Service: `ticket-workflow-service`
>
> Phase: 08
>
> Specs: `SPEC-TW-026` to `SPEC-TW-032`
>
> Prerequisite: Phase 01 to Phase 07 completed and accepted
>
> Document Status: Implementation Plan

## 1. Phase Goal

Phase 08 completes the tail governance of Ticket Workflow: confirming resolution, auto-closing, reopening, cancelling, assigning, escalating, and resuming escalated tickets.

Core paths:

```text
RESOLVED -> CLOSED
RESOLVED -> REOPENED -> IN_PROGRESS
OPEN/IN_PROGRESS/WAITING_FOR_USER/WAITING_FOR_APPROVAL/VERIFYING/RESOLVED -> CANCELLED
OPEN/IN_PROGRESS/WAITING_FOR_USER/WAITING_FOR_APPROVAL/VERIFYING/ESCALATED -> ASSIGNED/ESCALATED/IN_PROGRESS
```

Phase 08 does not execute tools or verify fixes. It consumes the Phase 07 resolution cycle and decides whether the Ticket can be closed, sent back into work, or routed through manual governance.

## 2. Design Boundaries

- `RESOLVED` is not `CLOSED`; confirmation or auto-close policy is required.
- Reopen creates a new resolution cycle and preserves previous resolution evidence.
- Cancel is terminal and cannot be reopened or resumed.
- Assignment changes ownership and queue metadata; it must not imply resolution.
- Escalation is a governance state, not a ticket failure.
- Resuming from escalation preserves the escalation audit trail.
- Stale close/reopen/escalation commands must not advance the current Ticket.
- Every Phase 08 command requires idempotency key, actor, reason, and audit event.

## 3. Phase 08 Specs

| Order | SPEC | Name | Responsibility |
|---|---|---|---|
| 1 | `SPEC-TW-026` | Confirm Resolution | Close a resolved Ticket through user or authorized confirmation |
| 2 | `SPEC-TW-027` | Auto Close | Close resolved Tickets through age/no-response policy |
| 3 | `SPEC-TW-028` | Reopen Ticket | Reopen `RESOLVED` or policy-eligible `CLOSED` Tickets |
| 4 | `SPEC-TW-029` | Cancel Ticket | Cancel a non-terminal Ticket |
| 5 | `SPEC-TW-030` | Assign Ticket | Move owner, team, queue, and assignee |
| 6 | `SPEC-TW-031` | Escalate Ticket | Escalate a Ticket to human or senior support governance |
| 7 | `SPEC-TW-032` | Resume Escalated Ticket | Resume work from `ESCALATED` |

## 4. State Transitions

| Current State | Target State | Trigger |
|---|---|---|
| `RESOLVED` | `CLOSED` | Confirm Resolution |
| `RESOLVED` | `CLOSED` | Auto Close |
| `RESOLVED` | `REOPENED` | Reopen Ticket |
| `CLOSED` | `REOPENED` | Reopen Ticket within policy window |
| `REOPENED` | `IN_PROGRESS` | New resolution cycle accepted |
| `OPEN`/`IN_PROGRESS`/`WAITING_FOR_USER`/`WAITING_FOR_APPROVAL`/`VERIFYING`/`RESOLVED` | `CANCELLED` | Cancel Ticket |
| Mutable states | same state | Assign Ticket |
| Mutable states | `ESCALATED` | Escalate Ticket |
| `ESCALATED` | `IN_PROGRESS` | Resume Escalated Ticket |

## 5. Events

Ticket Workflow publishes:

```text
ticket.resolution-confirmed.v1
ticket.auto-closed.v1
ticket.reopened.v1
ticket.cancelled.v1
ticket.assigned.v1
ticket.escalated.v1
ticket.escalation-resumed.v1
```

Ticket Workflow consumes:

```text
scheduler.auto-close-due.v1
support.assignment-commanded.v1
support.escalation-commanded.v1
```

## 6. Exit Criteria

- `SPEC-TW-026` to `SPEC-TW-032` docs, code, migrations, contracts, and tests are closed.
- `RESOLVED -> CLOSED` is allowed only by confirmation or auto-close.
- Reopen creates a new resolution cycle.
- Cancelled Tickets cannot be closed, reopened, escalated, or assigned.
- Assignment and escalation audit trails are queryable.
- Duplicate commands are idempotent.
- Stale commands do not advance the current workflow/cycle.
- Ticket Workflow has a complete lifecycle closure loop after Phase 08.
