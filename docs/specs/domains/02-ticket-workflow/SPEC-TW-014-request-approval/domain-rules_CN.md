# SPEC-TW-014 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | `SM-016` | `APPROVAL_REQUIRED` |

不变量：

- approval request 必须绑定 `ticketId`、`workflowId`、`actionId`、`actionType`；
- `approvalReference` 在 Ticket 当前等待期间不可被其他 action 复用；
- risk context 必须保存快照；
- `WAITING_FOR_APPROVAL` 不暂停 IT-owned SLA；
- wrong producer 不适用于 request 命令，只适用于后续 event consumers。
