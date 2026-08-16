# Phase 08 — Observability Recovery Admin

> Domain：Memory Knowledge
>
> Service：`memory-knowledge-service`
>
> Phase：08
>
> Specs：`SPEC-MK-028` ～ `SPEC-MK-030`
>
> 前置条件：`04-memory-knowledge` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

补齐 metrics/traces/audit、poison/recovery workers、admin repair/reindex/replay。

## 2. 范围

包含：

- 本 Phase 范围内 specs 的设计、代码、migration、测试和 traceability；
- Memory Knowledge 自有 aggregate、表、API、event、outbox 或 pipeline；
- 与 02/03 的契约闭环校验。

不包含：

- Ticket Workflow 主状态机重设计；
- Agent Runtime Workflow state 迁移；
- Tool Gateway 执行逻辑；
- Policy 自动批准逻辑；
- 跨 domain 分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-MK-028` | Memory observability 与 audit | 12-observability |
| 2 | `SPEC-MK-029` | Poison 与 recovery workers | 10-failure-handling |
| 3 | `SPEC-MK-030` | Admin repair、reindex 与 replay | 05-api-contracts, 10-failure-handling |

## 4. 强制约束

- Active memory 只能由受控 candidate/publish pipeline 创建；
- Retrieval result 必须携带 provenance；
- Graph traversal 必须 bounded，并且不能绕过 ACL/classification；
- 所有消费事件必须 processed-event 去重；
- 所有发布事件必须通过 Memory outbox；
- 04 不直接修改 Ticket state 或 Workflow state。

## 5. 退出条件

- 本 Phase 所有 spec 子目录存在，且中英文文档完整；
- 每个 spec 都有验收标准和测试计划；
- 与 02/03 的输入/输出契约在测试计划中可验证；
- 对应 LLD 章节没有未覆盖的关键规则。
