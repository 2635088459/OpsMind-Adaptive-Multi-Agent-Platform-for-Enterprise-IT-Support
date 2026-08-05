# Phase 06 — Tool Execution Slice

> Domain：Ticket Workflow
>
> Service：`ticket-workflow-service`
>
> Phase：06
>
> Specs：`SPEC-TW-019` ～ `SPEC-TW-021`
>
> 前置条件：Phase 01～05 已实现并通过验收
>
> 文档状态：Implementation Plan

## 1. Phase 目标

Phase 06 处理 Tool Gateway 对已授权 pending action 的执行结果，并把 Ticket 推进到需要验证、可继续调查、或需要人工升级的状态。

早期 roadmap 的目标是：

```text
EXECUTING -> VERIFYING
```

以及已知安全失败、未知结果和内部失败路径。

当前实现应保持以下边界：

- Phase 05 只保存 approval/policy authorization，不执行工具；
- Phase 06 只应用来自 trusted Tool Gateway 的执行结果；
- Tool 成功不能直接 Resolve；
- 只有 Verification 才能证明问题已解决；
- unknown side effect 不得盲目重试。

## 2. 状态模型

Phase 06 引入或确认以下执行相关状态：

```text
IN_PROGRESS -> EXECUTING
EXECUTING -> VERIFYING
EXECUTING -> IN_PROGRESS
EXECUTING -> ESCALATED
EXECUTING -> FAILED
```

如果当前代码尚未持久化 `EXECUTING` / `VERIFYING`，本 phase 的真实 migration 必须更新状态约束；如果实现选择用 execution attempt 表承载执行中状态，也必须保证查询投影仍能表达 `EXECUTING` 与 `VERIFYING` 的业务语义。

## 3. Phase 06 Specs

| 顺序 | SPEC | 名称 | 核心职责 |
|---|---|---|---|
| 1 | `SPEC-TW-019` | Tool Execution Completed | 成功执行结果进入验证 |
| 2 | `SPEC-TW-020` | Tool Execution Failed | 已知失败分类并安全恢复 |
| 3 | `SPEC-TW-021` | Tool Result Unknown | 未知副作用进入升级或核查路径 |

## 4. 事件

Ticket Workflow 消费：

```text
tool.execution.completed.v1
tool.execution.failed.v1
tool.execution.result-unknown.v1
```

Ticket Workflow 发布：

```text
ticket.tool-execution-completed-applied.v1
ticket.tool-execution-failed-applied.v1
ticket.tool-result-unknown-recorded.v1
```

## 5. 核心要求

- Tool event 必须来自 trusted producer；
- schema-invalid 或 wrong producer 进入 DLQ；
- `toolExecutionId` 是业务幂等 key；
- event 必须匹配 Ticket、workflow、action、authorization reference；
- stale event 不推进 Ticket；
- duplicate event 不产生重复业务效果；
- success 只进入 `VERIFYING`；
- known-safe failure 回到 `IN_PROGRESS`；
- pipeline/internal failure 可进入 `FAILED`；
- unknown result 或 unknown side effect 进入 `ESCALATED` 或 reconciliation-required。

## 6. 退出条件

- `SPEC-TW-019`～`SPEC-TW-021` 文档、代码、migration、contract 和测试闭环；
- Tool success 不直接 resolve；
- Tool execution 与当前 pending action 严格匹配；
- unknown result 不自动重试；
- ToolExecutionId 无重复业务效果；
- Phase 07 能基于 tool result reference 启动 verification。
