# Phase 07 — Verification and Resolution Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：07
>
> Specs：`SPEC-TW-022` ～ `SPEC-TW-025`
>
> 前置条件：Phase 01～06 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 07 在 Tool Execution 之后启动独立验证，并只允许 trusted verification evidence 推动 Ticket 进入 `RESOLVED`。

核心路径：

```text
VERIFYING -> RESOLVED
```

并包含 verification retry、failure limit、unsafe result escalation 和 resolution cycle 完整保存。

## 2. 设计边界

- Tool success 不是 resolution；
- proposal 不是 verification；
- verification 必须绑定当前 Ticket、workflow、resolution cycle、tool result 和 attempt；
- late verification from old workflow/cycle 只能记录为 stale；
- 第三次 verification failure 或 unsafe result 进入 `ESCALATED`；
- `RESOLVED` 不等于 `CLOSED`；
- Phase 07 的 `SPEC-TW-025-resolve-ticket` 是“基于 trusted verification evidence 的 resolution”，不同于 Phase 03 的人工 resolve command。

## 3. Phase 07 Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-022` | Start Verification | 基于 tool result 启动 verification attempt |
| 2 | `SPEC-TW-023` | Verification Success | 应用可信验证成功结果 |
| 3 | `SPEC-TW-024` | Verification Failure | 应用验证失败、重试或升级 |
| 4 | `SPEC-TW-025` | Resolve Ticket | 基于验证证据完成 resolution |

## 4. 状态转换

| 当前状态 | 目标状态 | 触发 |
|---|---|---|
| `VERIFYING` | `VERIFYING` | Start Verification |
| `VERIFYING` | `RESOLVED` | Resolve with trusted verification |
| `VERIFYING` | `IN_PROGRESS` | Retryable Verification Failure |
| `VERIFYING` | `ESCALATED` | Failure limit or unsafe result |
| `VERIFYING` | `FAILED` | Verification pipeline failure |

## 5. 事件

Ticket Workflow 发布：

```text
ticket.verification-started.v1
ticket.verification-success-applied.v1
ticket.verification-failure-applied.v1
ticket.resolved-with-verification.v1
```

Ticket Workflow 消费：

```text
verification.completed.v1
verification.failed.v1
```

## 6. 退出条件

- `SPEC-TW-022`～`SPEC-TW-025` 文档、代码、migration、contract 和测试闭环；
- resolution 必须引用当前 trusted verification evidence；
- duplicate verification result 幂等；
- conflicting verification terminal result 进入 reconciliation；
- stale workflow/cycle/attempt 不推进 Ticket；
- resolution cycle 完整保存，并可被 Phase 08 close/reopen 消费。
