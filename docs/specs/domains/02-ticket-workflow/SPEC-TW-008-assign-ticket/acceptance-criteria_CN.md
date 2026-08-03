# SPEC-TW-008 — 验收标准

## AC-01 — 首次分配

**Given** 一个无负责人的 `TRIAGED` Ticket、符合资格的 active Assignee、已授权 Actor、匹配的 `If-Match` 和新幂等键  
**When** 提交 Assign 命令  
**Then** 返回 `200 OK`，设置负责人，将状态从 `TRIAGED → ASSIGNED`，版本加一，返回新 `ETag`，并原子写入所有要求的记录。

## AC-02 — 重新分配

**Given** 一个处于允许状态的已分配 Ticket 和不同的合格 Assignee  
**When** 提交 Reassign 命令  
**Then** 替换负责人、保持 Ticket 状态不变、记录新旧负责人、版本加一，并发布 `ticket.reassigned.v1`。

## AC-03 — 取消分配

**Given** Ticket 状态为 `ASSIGNED`  
**When** 获得授权的 Actor 提交 Unassign  
**Then** 清除负责人，将状态从 `ASSIGNED → TRIAGED`，版本加一，并发布 `ticket.unassigned.v1`。

## AC-04 — 非法状态

- 在非 `TRIAGED` 状态执行 Assign，返回 `409 INVALID_TICKET_STATE`。
- 在允许范围外执行 Reassign，返回 `409 INVALID_TICKET_STATE`。
- 对 `IN_PROGRESS` 或 waiting Ticket 执行 Unassign，返回 `409 INVALID_TICKET_STATE`。
- 不得写入任何状态变更数据。

## AC-05 — Assignee 资格

不存在、inactive、跨 Tenant、非 Support Role 或不属于 Ticket Queue 的 Assignee 必须被拒绝，并返回稳定错误码且不产生写入。

## AC-06 — 授权

Requester、没有命令权限或没有队列权限的 Actor 返回 `403`。Actor Identity 与 Tenant 只能来自可信认证上下文。

## AC-07 — 乐观锁

过期或格式错误的 `If-Match` 必须被拒绝。两个使用相同版本的并发命令最多只能有一个提交成功。

## AC-08 — 幂等

相同 Key 与相同规范化命令的重放返回已保存结果；相同 Key 搭配不同命令指纹返回 `409 IDEMPOTENCY_KEY_REUSED`。

## AC-09 — 原子性

Ticket、负责人历史、状态历史、Timeline、Audit、Idempotency 或 Outbox 任一写入失败时，所有写入全部回滚。

## AC-10 — 可追踪性与隐私

每次成功操作都应按需包含 Actor、Correlation、Causation、Ticket、新旧负责人、时间与原因。Requester 可见 Timeline 不得泄露内部授权信息或秘密。

## AC-11 — 响应契约

成功响应返回 Ticket ID、状态、负责人摘要、分配时间、版本和 `ETag`。错误使用共享 Problem Details 结构。

## AC-12 — 可观测性

结构化日志与指标应区分 Assign、Reassign、Unassign 的结果，但不得记录 Bearer Token、完整幂等键或敏感用户属性。
