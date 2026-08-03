# SPEC-TW-011 — 验收标准

## AC-01 Close 成功

**Given** 一个 `RESOLVED` Ticket，存在当前 resolution cycle，actor 有队列关闭权限，`If-Match` 匹配且 idempotency key 未使用  
**When** 调用 `POST /api/v1/tickets/{ticketId}/closure` 并提供有效 `closeReason`  
**Then** 返回 `200 OK`，Ticket 进入 `CLOSED`，保存 `closedBy`、`closedAt`、`closeReason`，完成/关闭当前 cycle，版本加一，并原子写入 status history、timeline、audit、idempotency 和 `ticket.closed.v1` outbox。

## AC-02 Close 状态限制

- `IN_PROGRESS -> CLOSED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `WAITING_FOR_USER -> CLOSED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `CLOSED -> CLOSED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `OPEN/TRIAGED/ASSIGNED -> CLOSED` 返回 `409 INVALID_STATUS_TRANSITION`。

## AC-03 Close Reason

- `closeReason` 必填；
- trim 后长度为 3 到 500；
- reason code 必须属于受控枚举；
- 错误返回稳定 code `CLOSE_REASON_INVALID` 或 `VALIDATION_ERROR`。

## AC-04 Reopen RESOLVED

**Given** 一个 `RESOLVED` Ticket  
**When** 授权 actor 调用 `POST /api/v1/tickets/{ticketId}/reopen`  
**Then** Ticket 进入 `IN_PROGRESS`，`reopen_count` 加一，创建新 active resolution cycle，清空当前 resolution 字段，保留负责人，并写入 `ticket.reopened.v1`。

## AC-05 Reopen CLOSED

**Given** 一个 `CLOSED` Ticket  
**When** 授权 actor 在允许窗口内调用 reopen  
**Then** Ticket 进入 `IN_PROGRESS`，关闭字段从当前 Ticket 清空但保存在旧 cycle/history 中，新 cycle 成为当前 cycle。

## AC-06 Reopen Reason

- `reopenReason` 必填；
- trim 后长度为 10 到 1000；
- 可选 `reopenReasonCode` 必须属于受控枚举；
- 不允许包含 secret、token、password 或完整日志。

## AC-07 负责人

Reopen 默认保留原负责人。如果负责人为空或失效：

- 不得静默分配给其他人；
- response 必须包含 ownership warning；
- 后续 active work 必须由 assign/reassign 命令修正。

## AC-08 幂等

- 相同 idempotency key 和相同 payload replay 原始响应；
- 相同 key 不同 payload 返回 `409 IDEMPOTENCY_KEY_REUSED`；
- replay 不得产生第二条 timeline、history、audit 或 outbox。

## AC-09 并发

同一版本并发 close/reopen 只有一个成功。失败请求返回 `412 VERSION_CONFLICT` 或当前状态下的 `409 INVALID_STATUS_TRANSITION`，不得覆盖已提交结果。

## AC-10 事务原子性

Ticket、resolution cycle、status history、timeline、audit、outbox 和 finalized idempotency response 必须同事务提交。任一失败必须整体回滚。

## AC-11 事件

Close 只发布 `ticket.closed.v1`。Reopen 只发布 `ticket.reopened.v1`。失败命令不得发布成功事件。
