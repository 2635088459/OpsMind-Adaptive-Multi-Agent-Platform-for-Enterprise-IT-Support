# SPEC-TW-013 — 领域规则

## 状态转换

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `WAITING_FOR_USER` | `IN_PROGRESS` | `SM-015` | `USER_REPLIED` |

## 不变量

- actor 必须是 Ticket requester；
- request 必须为当前 open request；
- message 与 request answered 状态必须同事务；
- `waiting_for_requester_since` 成功后清空；
- `approval_reference` 不受影响；
- 不恢复 closed/resolved/cancelled Ticket；
- 不接受 body 中伪造 requesterId。

## 旧 Request

旧 request 的 reply 不允许恢复 Ticket。产品可选择保存为普通 message，但必须标记 `resumeApplied = false`。
