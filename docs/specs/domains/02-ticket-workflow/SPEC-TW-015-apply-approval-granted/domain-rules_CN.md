# SPEC-TW-015 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-017` | `APPROVAL_GRANTED` |

Approval granted 只能授权当前 pending action。已过期、已拒绝、已消费或引用不匹配的 approval 不得授权 execution。
