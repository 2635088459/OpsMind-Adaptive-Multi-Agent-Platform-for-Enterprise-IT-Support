# SPEC-TW-015 — Domain Rules

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-017` | `APPROVAL_GRANTED` |

Approval granted authorizes only the current pending action. Expired, rejected, consumed, or reference-mismatched approval cannot authorize execution.
