# SPEC-ARO-041 — Acceptance Criteria

Goal: support `Escalation Via Existing Triage`.

- A real triage call reaches `02-ticket-workflow`, and the ticket is genuinely routed into a real support queue.
- The employee-facing `escalation` response's `ticketId`/`assignedTeam` matches domain 09's `EscalationNotice` shape exactly.
- The escalated ticket is genuinely visible in `10-support-console`'s queue view afterward, with no further automated action attempted on it by this workflow instance.
