# SPEC-TW-032 — Resume Escalated Ticket

## 1. Goal

Resume an escalated ticket back into active work with preserved escalation audit history.

## 2. Scope

Includes:

- `POST /v1/tickets/{ticketId}/escalation/resume`;
- `ESCALATED -> IN_PROGRESS`;
- actor, reason, idempotency key, workflow version, and audit trail;
- `ticket.escalation-resumed.v1`.

Excludes:

- Phase 06 tool execution;
- Phase 07 verification evidence production;
- cross-domain data repair.

## 3. Core Rules

- The command must bind to the current Ticket version to avoid stale writes.
- The actor must be authorized for the action.
- Reason is required and written to timeline/audit.
- Duplicate idempotency keys return the same result.
- Terminal state commands are rejected.
- Resume must select a next owner/queue and cannot discard the escalation resolution notes.
