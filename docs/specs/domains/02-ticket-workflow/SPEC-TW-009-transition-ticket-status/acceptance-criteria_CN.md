# SPEC-TW-009 — 验收标准

## AC-01 — 开始处理

**Given** 一个 `ASSIGNED` Ticket，已有负责人，actor 被授权访问该队列，`If-Match` 匹配且 idempotency key 未使用  
**When** 提交目标状态 `IN_PROGRESS`  
**Then** 返回 `200 OK`，状态变为 `IN_PROGRESS`，版本加一，返回新 `ETag`，并原子写入 status history、timeline、audit、idempotency 和 outbox。

## AC-02 — 等待请求人

**Given** 一个 `IN_PROGRESS` Ticket  
**When** 提交目标状态 `WAITING_FOR_USER` 和 reason  
**Then** 状态变为 `WAITING_FOR_USER`，保存 `waitingForRequesterSince`，清空 `approvalReference`，并发布 `ticket.status-changed.v1`。

## AC-03 — 等待审批

**Given** 一个 `IN_PROGRESS` Ticket  
**When** 提交目标状态 `WAITING_FOR_APPROVAL`、reason 和 `approvalReference`  
**Then** 状态变为 `WAITING_FOR_APPROVAL`，保存 `approvalReference`，清空 `waitingForRequesterSince`，并发布 `ticket.status-changed.v1`。

## AC-04 — 恢复处理

**Given** 一个 `WAITING_FOR_USER` 或 `WAITING_FOR_APPROVAL` Ticket  
**When** 提交目标状态 `IN_PROGRESS`  
**Then** 状态恢复为 `IN_PROGRESS`，清理所有 waiting metadata，版本加一，并记录恢复原因。

## AC-05 — 非法转换

- `NEW -> IN_PROGRESS` 返回 `409 INVALID_STATUS_TRANSITION`。
- `TRIAGED -> IN_PROGRESS` 返回 `409 INVALID_STATUS_TRANSITION`。
- `ASSIGNED -> WAITING_FOR_USER` 返回 `409 INVALID_STATUS_TRANSITION`。
- `WAITING_FOR_USER -> WAITING_FOR_APPROVAL` 返回 `409 INVALID_STATUS_TRANSITION`。
- 通过本接口进入 `RESOLVED` 或 `CLOSED` 返回 `409 INVALID_STATUS_TRANSITION`。
- 非法转换不写入业务成功记录。

## AC-06 — 必须已有负责人

任何进入 `IN_PROGRESS`、`WAITING_FOR_USER` 或 `WAITING_FOR_APPROVAL` 的成功转换，都要求 Ticket 当前存在 `current_support_user_id`。缺少负责人时返回 `409 TICKET_NOT_ASSIGNED`。

## AC-07 — 授权

Requester 和缺少 `ticket:transition` scope 的 actor 返回 `403 FORBIDDEN`。具备 scope 但无目标队列权限的 actor 返回 `403 QUEUE_ACCESS_DENIED`。actor identity 只能来自可信认证上下文。

## AC-08 — 乐观锁

缺少或空白 `If-Match` 返回 `428 PRECONDITION_REQUIRED`。版本过期返回 `412 VERSION_CONFLICT`，并返回当前版本信息。两个同版本并发转换最多只能有一个提交成功。

## AC-09 — 幂等

相同 key 与相同规范化请求返回首次成功响应，不重复写入。相同 key 搭配不同请求 fingerprint 返回 `409 IDEMPOTENCY_KEY_REUSED`。

## AC-10 — 原子性

Ticket update、status history、timeline、audit、outbox 或 idempotency 任一写入失败，整个命令回滚。

## AC-11 — 契约与隐私

成功响应返回 ticket ID、previous status、current status、waiting metadata、version 和 `ETag`。错误使用共享 Problem Details。事件和日志不得包含 token、原始 claims、完整身份资料或私密消息内容。

## AC-12 — 可观测性

Metrics、structured logs 和 tracing 必须区分 start-work、wait-for-user、wait-for-approval、resume-work 的成功、拒绝、冲突和重放结果。
