# SPEC-TW-014 — Domain Rules

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | `SM-016` | `APPROVAL_REQUIRED` |

Invariants:

- approval request binds `ticketId`, `workflowId`, `actionId`, and `actionType`;
- `approvalReference` cannot be reused for another action while the ticket waits;
- risk context is snapshotted;
- `WAITING_FOR_APPROVAL` does not pause IT-owned SLA;
- wrong producer applies to later event consumers, not this request command.
