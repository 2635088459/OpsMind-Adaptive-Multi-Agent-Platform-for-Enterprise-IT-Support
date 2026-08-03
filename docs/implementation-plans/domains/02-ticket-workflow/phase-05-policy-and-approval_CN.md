# Phase 05 — Policy and Approval Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：05
>
> Specs：`SPEC-TW-014` ～ `SPEC-TW-018`
>
> 前置条件：Phase 01～04 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 05 为后续 Tool Execution 提供安全前置条件：Ticket Workflow 只在策略允许或审批通过后，才允许进入“可执行”路径。

早期 roadmap 使用：

```text
INVESTIGATING -> WAITING_FOR_APPROVAL -> EXECUTING
REJECTED / EXPIRED -> INVESTIGATING
```

当前 Phase 03/04 的持久化状态模型已收敛为：

```text
IN_PROGRESS -> WAITING_FOR_APPROVAL -> IN_PROGRESS
```

因此 Phase 05 的职责是：保存 pending action 与 approval reference，处理 approval domain 的 trusted events，并把 Ticket 恢复到 `IN_PROGRESS`，让 Phase 06 的 Tool Execution 专用命令继续推进。Phase 05 不直接执行工具。

## 2. 业务价值

高风险 IT 操作不能由 Tool Gateway 或 Agent 自行决定。Approval 必须绑定 Ticket、workflow、action 和 risk context，并且一次审批只能授权一次明确 action。

Phase 05 提供：

- request approval；
- approval granted/rejected/expired event handling；
- auto-approved policy decision；
- stale/wrong-producer/duplicate event 分类；
- approval 不可复用；
- expired approval 不可执行；
- timeline、audit、status history、outbox 和 idempotency。

## 3. 范围

### 3.1 包含

- `SPEC-TW-014-request-approval`
- `SPEC-TW-015-apply-approval-granted`
- `SPEC-TW-016-apply-approval-rejected`
- `SPEC-TW-017-apply-approval-expired`
- `SPEC-TW-018-apply-auto-approved-policy`
- `IN_PROGRESS -> WAITING_FOR_APPROVAL`
- `WAITING_FOR_APPROVAL -> IN_PROGRESS`
- pending action reference；
- approval reference；
- policy decision reference；
- event consumer validation；
- DLQ classification；
- transactional outbox。

### 3.2 不包含

- 真实 Approval Service 实现；
- 审批 UI；
- Tool execution；
- Verification；
- notification delivery；
- 组织审批策略编辑器；
- 高级风险模型训练。

## 4. Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-014` | Request Approval | 为 pending action 请求审批并进入等待审批 |
| 2 | `SPEC-TW-015` | Apply Approval Granted | 消费 approval granted 并记录可执行授权 |
| 3 | `SPEC-TW-016` | Apply Approval Rejected | 消费 approval rejected 并恢复处理 |
| 4 | `SPEC-TW-017` | Apply Approval Expired | 消费 approval expired 并恢复处理 |
| 5 | `SPEC-TW-018` | Apply Auto-Approved Policy | 应用低风险自动批准策略 |

## 5. 状态转换

| 当前状态 | 目标状态 | 触发 |
|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_APPROVAL` | Request Approval |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Approval Granted |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Approval Rejected |
| `WAITING_FOR_APPROVAL` | `IN_PROGRESS` | Approval Expired |
| `IN_PROGRESS` | `IN_PROGRESS` | Auto-Approved Policy |

Approval granted 不直接进入 Tool Execution；它记录授权结果并释放等待状态。Phase 06 负责执行。

## 6. 事件

Ticket Workflow 发布：

```text
ticket.approval-wait-started.v1
ticket.approval-granted-applied.v1
ticket.approval-rejected-applied.v1
ticket.approval-expired-applied.v1
ticket.auto-approval-applied.v1
```

Ticket Workflow 消费：

```text
approval.granted.v1
approval.rejected.v1
approval.expired.v1
policy.action-auto-approved.v1
```

## 7. 退出条件

- 所有 Phase 05 specs 文档、代码、migration、contract 和测试闭环；
- approval 绑定 Ticket、workflow、action、risk context；
- expired/rejected approval 不可授权 execution；
- duplicate granted/rejected/expired 幂等；
- wrong producer 或 schema-invalid event 进入 DLQ；
- stale event 不推进 Ticket；
- Phase 06 能基于已保存 authorization reference 继续 Tool Execution。
