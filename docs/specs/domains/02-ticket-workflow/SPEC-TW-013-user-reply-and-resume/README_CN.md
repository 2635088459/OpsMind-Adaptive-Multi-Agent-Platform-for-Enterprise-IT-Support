# SPEC-TW-013 — User Reply and Resume（用户回复并恢复处理）

## 1. 目标

允许 requester 回复当前 open user input request，并在同一事务中保存 message、关闭 request、将 Ticket 从 `WAITING_FOR_USER` 恢复到 `IN_PROGRESS`。

## 2. 范围

包含：

- `POST /api/v1/tickets/{ticketId}/user-input-requests/{requestId}/reply`
- requester reply message
- `WAITING_FOR_USER -> IN_PROGRESS`
- 关闭当前 input request
- 清理 `waiting_for_requester_since`
- status history、timeline、audit、outbox
- `ticket.user-reply-received.v1`
- `ticket.user-input-resumed.v1`

不包含：

- support 代表 requester 回复；
- notification delivery；
- Agent runtime 实际执行；
- timeout escalation；
- Approval。

## 3. 核心规则

- Ticket 必须属于当前 requester；
- Ticket 必须为 `WAITING_FOR_USER`；
- request 必须是当前 Ticket 的 open request；
- reply 必须引用当前 request；
- message 保存和状态恢复必须原子提交；
- 重复 reply 不得重复恢复；
- 旧 request 的 reply 可作为普通 message 保存，但不得恢复 Ticket。

## 4. 事件

```text
ticket.user-reply-received.v1
ticket.user-input-resumed.v1
```

## 5. 文件索引

- `acceptance-criteria_CN.md` / `acceptance-criteria_EN.md`
- `domain-rules_CN.md` / `domain-rules_EN.md`
- `api-contract_CN.md` / `api-contract_EN.md`
- `persistence_CN.md` / `persistence_EN.md`
- `event-contract_CN.md` / `event-contract_EN.md`
- `test-plan_CN.md` / `test-plan_EN.md`
- `openapi.yaml`
- `asyncapi.yaml`
- `examples.http`
- `V013__user_reply_and_resume.sql`
