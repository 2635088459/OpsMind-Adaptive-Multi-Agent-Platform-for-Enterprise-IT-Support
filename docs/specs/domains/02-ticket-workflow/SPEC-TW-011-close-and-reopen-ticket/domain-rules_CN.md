# SPEC-TW-011 — 领域规则

## 1. 状态转换

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `RESOLVED` | `CLOSED` | `SM-011` | `TICKET_CLOSED` |
| `RESOLVED` | `IN_PROGRESS` | `SM-012` | `TICKET_REOPENED` |
| `CLOSED` | `IN_PROGRESS` | `SM-013` | `TICKET_REOPENED` |

未列出的 close/reopen 转换全部拒绝。

## 2. Close 不变量

Close 必须：

1. 要求当前状态为 `RESOLVED`；
2. 要求当前 resolution cycle 存在且已 resolved；
3. 要求 `closeReason` 有效；
4. 设置 `status = CLOSED`；
5. 设置 `closedAt`、`closedBy`、`closeReasonCode`；
6. 清空 `autoCloseDueAt` 和 `activeWorkflowId`；
7. 将当前 resolution cycle 标记为 `CLOSED`；
8. 保留负责人、resolved fields 和历史快照；
9. version 加一。

Close 不得：

- 从 `IN_PROGRESS` 或 waiting 状态直接关闭；
- 修改 requester、category、priority、queue 或 assignee；
- 删除 resolution summary；
- 发布 reopen event。

## 3. Reopen 不变量

Reopen 必须：

1. 要求当前状态为 `RESOLVED` 或 `CLOSED`；
2. 要求 `reopenReason` 非空且长度有效；
3. 关闭或归档旧的当前 cycle；
4. 创建新的 active resolution cycle；
5. 设置 `currentResolutionCycleId` 为新 cycle；
6. 设置 `status = IN_PROGRESS`；
7. `reopenCount` 加一；
8. 清空当前 Ticket 的 `resolvedAt`、`resolvedBy`、`resolutionCode`、`resolutionSummary`、`closedAt`、`closedBy`、`closeReasonCode`、`autoCloseDueAt`；
9. 保留原负责人；
10. version 加一。

旧 cycle/history 必须保留上一轮解决和关闭快照，不能因清空当前 Ticket 字段而丢失。

## 4. 负责人规则

Reopen 不自动选择新负责人。原负责人仍 active 且有队列权限时继续保留。若原负责人失效：

- Ticket 仍可进入 `IN_PROGRESS`；
- response 返回 `ownershipStatus = ASSIGNEE_INACTIVE`；
- 后续 start/active work 相关命令必须要求先 reassign。

## 5. Reopen Window

早期 state machine 曾冻结 `CLOSED` 后 7 天内可 reopen。Phase 03 当前文档只写明允许 `CLOSED -> IN_PROGRESS`，未要求本 SPEC 实现硬性窗口。推荐实现保留配置项：

```text
ticket.reopenWindow = P7D
```

默认启用窗口时，超出窗口返回 `422 REOPEN_WINDOW_EXPIRED`。若产品决定本 phase 不限制窗口，应在配置中显式关闭并在测试中固定。

## 6. 幂等指纹

Close 指纹包含：

- `ticketId`
- expected version
- `closeReasonCode`
- normalized `closeReason`

Reopen 指纹包含：

- `ticketId`
- expected version
- `reopenReasonCode`
- normalized `reopenReason`

## 7. 安全

服务端从 token 派生 actor，不接受 body 中的 `closedBy` 或 `reopenedBy`。日志、事件和 metric label 不包含 idempotency key、Authorization header、secret 或完整 reason 文本。
