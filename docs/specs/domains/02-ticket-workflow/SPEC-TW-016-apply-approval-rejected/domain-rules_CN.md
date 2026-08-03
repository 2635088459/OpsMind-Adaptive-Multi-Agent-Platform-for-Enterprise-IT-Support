# SPEC-TW-016 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-018` | `APPROVAL_REJECTED` |

拒绝后必须清理当前 approval reference，并记录 rejection snapshot。后续若仍需执行，必须创建新的 action 和新的 approval。
