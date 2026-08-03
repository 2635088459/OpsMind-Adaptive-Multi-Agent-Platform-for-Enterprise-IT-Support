# SPEC-TW-011 — Close and Reopen Ticket（关闭与重新打开工单）

## 1. 目标

`SPEC-TW-011` 完成 Phase 03 的最后一段生命周期：

- 将已解决 Ticket 从 `RESOLVED` 关闭为 `CLOSED`；
- 将 `RESOLVED` 或 `CLOSED` Ticket 重新打开为 `IN_PROGRESS`；
- 为 reopen 创建新的 resolution cycle；
- 保留旧周期的 resolution/close 历史快照；
- 写入一致的 status history、timeline、audit、outbox 和 idempotency 记录。

本 SPEC 继承 `SPEC-TW-007` 到 `SPEC-TW-010` 已建立的命令边界、版本控制、权限模型和事件投递模式。

## 2. 权威来源

- `docs/implementation-plans/domains/02-ticket-workflow/phase-03-ticket-lifecycle-and-ownership_CN.md`
- `docs/implementation-plans/domains/02-ticket-workflow/phase-03-ticket-lifecycle-and-ownership_EN.md`
- `docs/low-level-design/domains/02-ticket-workflow/03-state-machine/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/06-event-contracts/README_EN.md`
- `docs/low-level-design/domains/02-ticket-workflow/07-data-model/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/09-concurrency-and-idempotency/README_CN.md`

若早期 low-level state machine 与 Phase 03 implementation plan 冲突，以 Phase 03 为准。当前持久化状态是：

```text
OPEN -> TRIAGED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED
```

`REOPENED` 不是长期状态；reopen 成功后 Ticket 进入 `IN_PROGRESS`。

## 3. 范围

### 3.1 包含

- `POST /api/v1/tickets/{ticketId}/closure`
- `POST /api/v1/tickets/{ticketId}/reopen`
- `RESOLVED -> CLOSED`
- `RESOLVED -> IN_PROGRESS`
- `CLOSED -> IN_PROGRESS`
- `closeReason` / `closedBy` / `closedAt`
- `reopenReason` / `reopenedBy` / `reopenedAt`
- `reopen_count`
- 新 resolution cycle
- status history、resolution cycle history、timeline、audit、outbox
- `ticket.closed.v1`
- `ticket.reopened.v1`

### 3.2 不包含

- 自动关闭 scheduler；
- 关闭确认 UI；
- notification delivery；
- SLA 引擎重算；
- 自动重新分配；
- long-running workflow restart；
- cancel、escalate 或 incident/problem/change 关联。

## 4. Close 语义

Close 是终止当前已解决周期的业务命令。只允许：

```text
RESOLVED -> CLOSED
```

成功后：

- Ticket 状态为 `CLOSED`；
- 保存 `closedBy`、`closedAt`、`closeReason`；
- 当前 resolution cycle 标记为 `CLOSED`；
- 普通状态转换接口不得再修改该 Ticket；
- 只有 reopen 专用命令可以使其重新进入工作流。

## 5. Reopen 语义

Reopen 表示问题复发、解决无效或 requester/support 决定重新处理。允许：

```text
RESOLVED -> IN_PROGRESS
CLOSED   -> IN_PROGRESS
```

成功后：

- Ticket 状态为 `IN_PROGRESS`；
- `reopen_count` 加一；
- 创建新的 active resolution cycle；
- `current_resolution_cycle_id` 指向新周期；
- 清空当前 resolution/close 字段；
- 保留旧周期内的历史快照；
- 保留原负责人；
- 如果原负责人失效，则 Ticket 仍可 reopen，但后续 active work 必须先重新分配或由授权角色修正负责人。

## 6. 权限

- Close：Support Lead 或授权 Support Agent；Requester 不得直接调用 support close API。
- Reopen：Support Lead、授权 Support Agent，或按后续产品决策开放 requester reopen endpoint；本 SPEC 的 endpoint 默认是 support command。
- Automation Agent 必须使用明确授予的 service identity。
- 所有权限必须在服务端验证，并基于 Ticket 的 support queue 做 queue-level authorization。

## 7. 幂等与并发

所有命令要求：

```text
Authorization
Idempotency-Key
If-Match
X-Correlation-ID
```

相同 key 和相同 payload 必须 replay 首次结果；相同 key 不同 payload 返回 idempotency conflict。版本不匹配返回 `412 VERSION_CONFLICT`，不得 last-write-wins。

## 8. 错误代码

`TICKET_NOT_FOUND`、`INVALID_STATUS_TRANSITION`、`CLOSE_REASON_INVALID`、`REOPEN_REASON_INVALID`、`RESOLUTION_CYCLE_NOT_FOUND`、`ASSIGNEE_INACTIVE`、`FORBIDDEN`、`QUEUE_ACCESS_DENIED`、`VERSION_CONFLICT`、`PRECONDITION_REQUIRED`、`IDEMPOTENCY_KEY_REUSED`、`REQUEST_IN_PROGRESS`、`VALIDATION_ERROR`。

## 9. 文件索引

- `acceptance-criteria_CN.md` / `acceptance-criteria_EN.md`
- `domain-rules_CN.md` / `domain-rules_EN.md`
- `api-contract_CN.md` / `api-contract_EN.md`
- `persistence_CN.md` / `persistence_EN.md`
- `event-contract_CN.md` / `event-contract_EN.md`
- `test-plan_CN.md` / `test-plan_EN.md`
- `openapi.yaml`
- `asyncapi.yaml`
- `examples.http`
- `V011__close_and_reopen_ticket.sql`
