# SPEC-TW-010 — 验收标准

## AC-01 — 成功解决

**Given** 一个 `IN_PROGRESS` Ticket，已有负责人，存在 active resolution cycle，actor 被授权访问该队列，`If-Match` 匹配且 idempotency key 未使用  
**When** 提交有效 `resolutionCode` 和 `resolutionSummary`  
**Then** 返回 `200 OK`，状态变为 `RESOLVED`，保存解决信息，完成 resolution cycle，版本加一，返回新 `ETag`，并原子写入 history、timeline、audit、idempotency 和 outbox。

## AC-02 — Resolution Summary

`resolutionSummary` trim 后必须为 10 到 5000 字符。空白、过短或超长返回 `400 VALIDATION_ERROR`。

## AC-03 — Resolution Code

不在受控枚举中的 `resolutionCode` 返回 `400 VALIDATION_ERROR` 或 `422 RESOLUTION_CODE_INVALID`，且不产生业务写入。

## AC-04 — 非法状态

- `ASSIGNED -> RESOLVED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `WAITING_FOR_USER -> RESOLVED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `WAITING_FOR_APPROVAL -> RESOLVED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `RESOLVED -> RESOLVED` 返回 `409 INVALID_STATUS_TRANSITION`。
- `CLOSED -> RESOLVED` 返回 `409 INVALID_STATUS_TRANSITION`。

## AC-05 — 必须已有负责人

缺少 `current_support_user_id` 的 Ticket 不能解决，返回 `409 TICKET_NOT_ASSIGNED`。

## AC-06 — Resolution Cycle

当前 `current_resolution_cycle_id` 必须存在且未完成。缺失返回 `409 RESOLUTION_CYCLE_NOT_FOUND`，已完成返回 `409 RESOLUTION_CYCLE_ALREADY_COMPLETED`。

## AC-07 — Metadata 清理

成功解决后必须清理 `waiting_for_requester_since` 和 `approval_reference`，并设置 `resolved_by`、`resolved_at`、`resolution_code` 和 `resolution_summary`。

## AC-08 — 授权

Requester 和缺少 `ticket:resolve` scope 的 actor 返回 `403 FORBIDDEN`。具备 scope 但无队列权限的 actor 返回 `403 QUEUE_ACCESS_DENIED`。

## AC-09 — 乐观锁

缺少或空白 `If-Match` 返回 `428 PRECONDITION_REQUIRED`。版本过期返回 `412 VERSION_CONFLICT`，并返回当前版本信息。两个同版本并发 resolve 最多只能有一个提交成功。

## AC-10 — 幂等

相同 key 与相同规范化请求返回首次成功响应，不重复写入。相同 key 搭配不同请求 fingerprint 返回 `409 IDEMPOTENCY_KEY_REUSED`。

## AC-11 — 原子性

Ticket update、resolution cycle update、status history、timeline、audit、outbox 或 idempotency 任一写入失败，整个命令回滚。

## AC-12 — 契约与隐私

成功响应返回 ticket ID、previous status、status、resolution code、summary、resolvedBy、resolvedAt 和 version。事件和日志不得包含 token、原始 claims、私密消息或 secret。
