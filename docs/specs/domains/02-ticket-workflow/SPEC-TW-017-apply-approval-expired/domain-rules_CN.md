# SPEC-TW-017 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | `SM-019` | `APPROVAL_EXPIRED` |

过期后必须清理 approval reference。后续执行必须重新申请审批或走新的 auto-approved policy。
