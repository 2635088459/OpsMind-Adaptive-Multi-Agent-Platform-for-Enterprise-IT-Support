# SPEC-TW-012 — 验收标准

## AC-01 成功请求用户输入

**Given** 一个 `IN_PROGRESS` Ticket，已有负责人，无 open user input request，actor 有队列权限，`If-Match` 匹配且 idempotency key 未使用  
**When** 调用 request user input endpoint 并提供有效 prompt  
**Then** 返回 `201 Created`，Ticket 进入 `WAITING_FOR_USER`，创建 open request，设置 `waitingForRequesterSince`，版本加一，并原子写入 history、timeline、audit、outbox 和 idempotency response。

## AC-02 状态限制

- 非 `IN_PROGRESS` 状态返回 `409 INVALID_STATUS_TRANSITION`。
- `WAITING_FOR_USER` 再次请求返回 `409 USER_INPUT_REQUEST_ALREADY_OPEN`。
- `RESOLVED`、`CLOSED`、`OPEN`、`TRIAGED`、`ASSIGNED` 不得进入等待用户。

## AC-03 Open Request 唯一性

同一 Ticket 同时最多一个 `OPEN` request。并发创建只能一个成功，另一个返回 conflict 或 version mismatch。

## AC-04 Prompt

Prompt 必填，trim 后长度 10 到 2000，不得包含 secret、token、password、private key、内部工具日志或对用户不安全的指令。

## AC-05 权限

Requester 不得创建 support user-input request。Support actor 必须有 queue-level authorization。Automation Agent 必须使用 service identity。

## AC-06 幂等

相同 key/payload replay 首次响应，不产生重复 request、history、timeline 或 outbox。相同 key 不同 payload 返回 `409 IDEMPOTENCY_KEY_REUSED`。

## AC-07 事件

成功只发布 `ticket.user-input-requested.v1`。失败不得发布成功事件。
