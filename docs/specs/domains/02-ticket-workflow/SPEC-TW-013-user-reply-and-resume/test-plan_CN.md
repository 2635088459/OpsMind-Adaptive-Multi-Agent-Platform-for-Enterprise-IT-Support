# SPEC-TW-013 — TDD 测试计划

覆盖：

- `WAITING_FOR_USER -> IN_PROGRESS` 成功；
- requester ownership 验证；
- request 不存在/不属于 Ticket/非 OPEN；
- 非 waiting 状态拒绝恢复；
- message 校验和附件引用校验；
- duplicate reply 只恢复一次；
- idempotency replay/conflict；
- 并发两个 reply 只有一个 status transition；
- transaction rollback；
- `ticket.user-reply-received.v1` 与 `ticket.user-input-resumed.v1` contract；
- E2E：request input -> requester reply -> resume。
