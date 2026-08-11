# Agent Runtime Orchestration — Implementation Roadmap

> Domain：Agent Runtime Orchestration
>
> Service：`agent-runtime-service`
>
> LLD：`docs/low-level-design/domains/03-agent-runtime-orchestration`
>
> Spec Prefix：`SPEC-ARO`
>
> 文档状态：Implementation Plan

## 1. 目标

本 roadmap 把 03 Agent Runtime Orchestration 的 14 个 LLD 切面转化为可实现的 phase/spec 计划。

Runtime 的职责是编排 Agent 自动化执行；它不拥有 Ticket lifecycle state。Ticket state 由 Ticket Workflow 管理，Runtime 只维护 Agent Workflow state，并通过事件、只读查询和受控 command 边界协作。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| Phase 00 | 工程基础 | `SPEC-ARO-001` ～ `SPEC-ARO-003` | 建立 Agent Runtime Orchestration 的工程边界、schema 基线、outbox 与幂等基础设施。 |
| Phase 01 | Workflow Instance 生命周期 | `SPEC-ARO-004` ～ `SPEC-ARO-006` | 定义 Workflow Instance aggregate，并从 `ticket.created` 启动自动化编排实例。 |
| Phase 02 | Agent Task 编排 | `SPEC-ARO-007` ～ `SPEC-ARO-010` | 建立 Agent Task、planner task graph、claim lease、completion 和 join policy。 |
| Phase 03 | Checkpoint 与 Cursor | `SPEC-ARO-011` ～ `SPEC-ARO-013` | 实现可恢复 checkpoint、等待状态快照和事件处理 cursor/processed-event 去重。 |
| Phase 04 | Pause Resume 控制 | `SPEC-ARO-014` ～ `SPEC-ARO-016` | 实现 pause/resume command 幂等、pause generation 和 stale worker result 防护。 |
| Phase 05 | Tool Gateway 中介 | `SPEC-ARO-017` ～ `SPEC-ARO-020` | 强制 Agent 不能直连 Tool，所有工具副作用必须通过 Runtime 持久化 Tool Request 并经 Tool Gateway。 |
| Phase 06 | 外部事件消费 | `SPEC-ARO-021` ～ `SPEC-ARO-024` | 消费 approval、verification、ticket cycle 变化等外部事件，并处理 duplicate、stale、invalid 分类。 |
| Phase 07 | Runtime 事件发布 | `SPEC-ARO-025` ～ `SPEC-ARO-027` | 通过 Runtime outbox 发布 workflow 和 agent.task 事件，不让同步 broker 发布污染事务边界。 |
| Phase 08 | 失败恢复与对账 | `SPEC-ARO-028` ～ `SPEC-ARO-031` | 实现 Runtime 崩溃恢复、过期 lease 恢复、outbox replay 和人工对账修复入口。 |
| Phase 09 | 安全观测与发布就绪 | `SPEC-ARO-032` ～ `SPEC-ARO-036` | 补齐权限、脱敏、metrics/tracing、契约测试与最终 phase/spec 覆盖审计。 |

## 3. 关键设计回答

- Workflow Instance：一次围绕 ticket/cycle 的自动化编排实例。
- Agent Task：Workflow 内部派发给 Agent role 的最小可调度工作单元。
- Checkpoint：结构化 JSON payload + version + cursor + checksum 的持久化恢复点。
- Pause / Resume：通过 idempotency key、workflow version、pause generation 和 outbox 去重保证幂等。
- 多 Agent 编排：通过 planner、task graph、claim lease、join policy 和 coordinator 完成。
- 崩溃恢复：通过 checkpoint、pending task、event cursor、outbox replay 和 lease expiry 恢复。
- 事件消费：`ticket.created`、`approval.granted`、`tool.completed`、`verification.completed` 必须去重并校验 correlation。
- 事件发布：`workflow.started`、`workflow.paused`、`agent.task.completed` 必须经 Runtime outbox。
- Tool 调用：Agent 不能直接调用 Tool，必须经过 Tool Gateway。
- 状态分离：Agent Workflow state 与 Ticket state 分离，不共享状态机和事务边界。

## 4. 实施顺序

按 Phase 00 到 Phase 09 顺序推进。每个 spec 应先补齐文档、验收标准、迁移和测试计划，再进入代码实现。

## 5. 审计点

Phase 完成后必须生成 traceability audit，检查 spec 是否覆盖 14 个 LLD 切面、关键事件、幂等策略、Tool Gateway 边界和崩溃恢复路径。
