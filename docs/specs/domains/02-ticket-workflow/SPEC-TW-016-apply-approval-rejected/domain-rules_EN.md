# SPEC-TW-016 — Domain Rules

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-018` | `APPROVAL_REJECTED` |

After rejection, clear current approval reference and record the rejection snapshot. Later execution requires a new action and a new approval.
