# SPEC-TW-012 — Request User Input（请求用户补充信息）

## 1. 目标

允许授权 support actor 或 Automation Agent 在 Ticket 处理中向 requester 请求补充信息，并将 Ticket 从 `IN_PROGRESS` 推进到 `WAITING_FOR_USER`。

成功命令必须创建一个 open user input request，记录 requester-facing prompt，保存 waiting metadata，并写入 status history、timeline、audit、outbox 和 idempotency response。

## 2. 权威来源

- `phase-04-waiting-for-user_CN.md`
- `phase-04-waiting-for-user_EN.md`
- `phase-03-ticket-lifecycle-and-ownership_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/03-state-machine/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/07-data-model/README_CN.md`
- `docs/low-level-design/domains/02-ticket-workflow/09-concurrency-and-idempotency/README_CN.md`

若早期 state machine 使用 `TRIAGING / INVESTIGATING`，当前 Phase 04 将其映射到 `IN_PROGRESS`。

## 3. 范围

包含：

- `POST /api/v1/tickets/{ticketId}/user-input-requests`
- `IN_PROGRESS -> WAITING_FOR_USER`
- 创建 `ticket_user_input_requests`
- 确保每个 Ticket 只有一个 open request
- 记录 prompt、requestedBy、requestedAt、resumeStatus
- 设置 `waiting_for_requester_since`
- status history、timeline、audit、outbox
- `ticket.user-input-requested.v1`

不包含：

- requester reply；
- notification delivery；
- SLA breach engine；
- timeout escalation；
- Approval 或 Tool execution。

## 4. 核心规则

- 仅允许 `IN_PROGRESS -> WAITING_FOR_USER`；
- Ticket 必须已有负责人；
- actor 必须有目标 support queue 权限；
- prompt 必须面向 requester，可安全展示；
- 同一 Ticket 不允许存在第二个 `OPEN` user input request；
- 成功后保留负责人；
- 成功后普通 resolve/close 必须继续受状态机约束，不得绕过等待用户。

## 5. 事件

```text
ticket.user-input-requested.v1
```

## 6. 文件索引

- `acceptance-criteria_CN.md` / `acceptance-criteria_EN.md`
- `domain-rules_CN.md` / `domain-rules_EN.md`
- `api-contract_CN.md` / `api-contract_EN.md`
- `persistence_CN.md` / `persistence_EN.md`
- `event-contract_CN.md` / `event-contract_EN.md`
- `test-plan_CN.md` / `test-plan_EN.md`
- `openapi.yaml`
- `asyncapi.yaml`
- `examples.http`
- `V012__request_user_input.sql`
