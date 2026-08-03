# SPEC-TW-013 — 验收标准

## AC-01 成功回复并恢复

**Given** Ticket 为 `WAITING_FOR_USER`，requestId 指向当前 open request，actor 是 Ticket requester，`If-Match` 匹配且 idempotency key 未使用  
**When** requester 提交有效 reply  
**Then** 保存 message，request 变为 `ANSWERED`，Ticket 进入 `IN_PROGRESS`，清理 waiting metadata，版本加一，并原子写入 history、timeline、audit、outbox。

## AC-02 状态和 request 限制

- Ticket 非 `WAITING_FOR_USER` 不得恢复；
- request 不属于 Ticket 返回 404 或 403；
- request 非 `OPEN` 不得恢复；
- 旧 request reply 不得恢复当前 Ticket。

## AC-03 Reply 内容

Reply 必填，trim 后长度 1 到 10000。附件引用必须属于当前 Ticket/request 且已完成安全扫描。

## AC-04 幂等与重复回复

相同 key/payload replay 首次响应。并发回复同一 request 只有一个恢复成功；后续回复可保存为普通 message，但不得再次 status transition。

## AC-05 事件

有效当前 reply 发布 `ticket.user-reply-received.v1` 和 `ticket.user-input-resumed.v1`。普通 message 只发布 message 事件，不发布 resumed event。
