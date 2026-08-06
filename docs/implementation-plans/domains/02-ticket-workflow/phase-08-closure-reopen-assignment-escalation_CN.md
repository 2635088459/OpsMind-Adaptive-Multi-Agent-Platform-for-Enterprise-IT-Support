# Phase 08 — Closure, Reopen, Assignment, and Escalation Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：08
>
> Specs：`SPEC-TW-026` ～ `SPEC-TW-032`
>
> 前置条件：Phase 01～07 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 08 完成 Ticket Workflow 的后段治理闭环：确认解决、自动关闭、重新打开、取消、转派、升级，以及从升级状态恢复。

核心路径：

```text
RESOLVED -> CLOSED
RESOLVED -> REOPENED -> IN_PROGRESS
OPEN/IN_PROGRESS/WAITING_FOR_USER/WAITING_FOR_APPROVAL/VERIFYING/RESOLVED -> CANCELLED
OPEN/IN_PROGRESS/WAITING_FOR_USER/WAITING_FOR_APPROVAL/VERIFYING/ESCALATED -> ASSIGNED/ESCALATED/IN_PROGRESS
```

Phase 08 不再负责工具执行或验证本身；它消费 Phase 07 产出的 resolution cycle，并决定 Ticket 是否真正关闭、回流或进入人工治理。

## 2. 设计边界

- `RESOLVED` 不等于 `CLOSED`，必须经过确认或 auto-close policy；
- reopen 必须创建新的 resolution cycle，并保留上一轮 resolution evidence；
- cancel 是 terminal state，不能被 reopen/resume；
- assign 只改变 ownership/queue，不应隐式改变业务 resolution；
- escalate 是治理状态，不代表 ticket failed；
- resume escalated ticket 必须保留 escalation audit trail；
- stale close/reopen/escalation command 不能推进当前 Ticket；
- 所有 Phase 08 command 必须具备 idempotency key、actor、reason 和 audit event。

## 3. Phase 08 Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-026` | Confirm Resolution | 用户或授权 actor 确认解决并关闭 Ticket |
| 2 | `SPEC-TW-027` | Auto Close | 基于 resolved age / no-response policy 自动关闭 |
| 3 | `SPEC-TW-028` | Reopen Ticket | 从 `RESOLVED` 或 `CLOSED` 合规重新打开 |
| 4 | `SPEC-TW-029` | Cancel Ticket | 取消尚未终结的 Ticket |
| 5 | `SPEC-TW-030` | Assign Ticket | 转派 owner、team、queue 和 assignee |
| 6 | `SPEC-TW-031` | Escalate Ticket | 将 Ticket 升级到人工/高级队列 |
| 7 | `SPEC-TW-032` | Resume Escalated Ticket | 从 `ESCALATED` 恢复到可处理状态 |

## 4. 状态转换

| 当前状态 | 目标状态 | 触发 |
|---|---|---|
| `RESOLVED` | `CLOSED` | Confirm Resolution |
| `RESOLVED` | `CLOSED` | Auto Close |
| `RESOLVED` | `REOPENED` | Reopen Ticket |
| `CLOSED` | `REOPENED` | Reopen Ticket within policy window |
| `REOPENED` | `IN_PROGRESS` | New resolution cycle accepted |
| `OPEN`/`IN_PROGRESS`/`WAITING_FOR_USER`/`WAITING_FOR_APPROVAL`/`VERIFYING`/`RESOLVED` | `CANCELLED` | Cancel Ticket |
| mutable states | same state | Assign Ticket |
| mutable states | `ESCALATED` | Escalate Ticket |
| `ESCALATED` | `IN_PROGRESS` | Resume Escalated Ticket |

## 5. 事件

Ticket Workflow 发布：

```text
ticket.resolution-confirmed.v1
ticket.auto-closed.v1
ticket.reopened.v1
ticket.cancelled.v1
ticket.assigned.v1
ticket.escalated.v1
ticket.escalation-resumed.v1
```

Ticket Workflow 消费：

```text
scheduler.auto-close-due.v1
support.assignment-commanded.v1
support.escalation-commanded.v1
```

## 6. 退出条件

- `SPEC-TW-026`～`SPEC-TW-032` 文档、代码、migration、contract 和测试闭环；
- `RESOLVED -> CLOSED` 只能由 confirm 或 auto-close 推进；
- reopen 必须创建新的 resolution cycle；
- cancel 后不可再 close/reopen/escalate/assign；
- assignment/escalation audit trail 可追溯；
- duplicate command 幂等；
- stale command 不推进当前 workflow/cycle；
- Phase 08 结束后 Ticket Workflow 具备完整生命周期闭环。
