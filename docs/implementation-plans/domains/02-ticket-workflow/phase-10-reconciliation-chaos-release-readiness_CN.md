# Phase 10 — Reconciliation, Chaos and Release Readiness Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：10
>
> Specs：`SPEC-TW-037` ～ `SPEC-TW-041`
>
> 前置条件：Phase 01～09 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 10 验证 Ticket Workflow 在重复、乱序、崩溃、未知结果、DLQ、跨服务冲突和数据不一致场景下仍能安全恢复，并达到 release readiness。

核心路径不是新的业务 happy path，而是 recovery path：

```text
Unknown / Duplicate / Out-of-order / Crash Window
-> Reconciliation Case
-> Replay / Correction / Compensation / Repair
-> Auditable Stable State
```

## 2. 设计边界

- Phase 10 不引入新的主生命周期 happy path；
- reconciliation 不得绕过 state machine guard；
- replay 不得破坏 event idempotency；
- correction event 必须显式、可审计、可追踪，不能静默改历史；
- compensation 只能通过已定义的补偿动作推进，不能直接改 JPA entity 状态；
- integrity repair 必须先生成 case，再执行受控 repair；
- chaos/performance/release gate 是验证能力，不替代业务测试；
- 所有恢复动作必须保留 actor、reason、correlationId、causationId 和 audit trail。

## 3. Phase 10 Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-037` | Open Reconciliation Case | 为未知/冲突/不一致打开 reconciliation case |
| 2 | `SPEC-TW-038` | Replay Event | 安全重放事件、outbox 或 DLQ 消息 |
| 3 | `SPEC-TW-039` | Correction Event | 发布显式 correction event 修正错误事实 |
| 4 | `SPEC-TW-040` | Compensation | 执行受控补偿动作恢复业务一致性 |
| 5 | `SPEC-TW-041` | Data Integrity Repair | 扫描并修复受控数据完整性问题 |

## 4. 恢复分类

| 分类 | 例子 | 处理方式 |
|---|---|---|
| Duplicate | 同一 event/command 重复到达 | 幂等 replay，记录 duplicate decision |
| Out-of-order | verification 早于 tool result 到达 | stale/retry/reconciliation case |
| Unknown Result | tool result unknown 或 crash window | open case，等待证据或补偿 |
| Broken Projection | timeline/query projection 缺失 | integrity scan + repair case |
| Bad Fact | 已发布事件包含错误字段 | correction event，不改写历史 |
| Side Effect Conflict | 外部系统状态与 Ticket 不一致 | compensation action |

## 5. 事件与审计

Ticket Workflow 发布或记录：

```text
ticket.reconciliation-case-opened.v1
ticket.event-replay-recorded.v1
ticket.correction-event-published.v1
ticket.compensation-executed.v1
ticket.integrity-repair-applied.v1
```

## 6. 退出条件

- `SPEC-TW-037`～`SPEC-TW-041` 文档、代码、contract 和测试闭环；
- duplicate/replay/out-of-order/crash-window 场景可验证；
- correction 和 compensation 均可审计；
- integrity repair 不直接静默修改业务状态；
- release gate 覆盖 golden path、recovery path、security hardening 和 performance smoke；
- Phase 01～09 的核心测试仍通过。
