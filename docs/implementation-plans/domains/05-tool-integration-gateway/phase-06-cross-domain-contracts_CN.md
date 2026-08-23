# Phase 06 — 跨域契约闭环

> Domain：Tool Integration Gateway
>
> Service：`tool-integration-gateway`
>
> Phase：06
>
> Specs：`SPEC-TG-022` ～ `SPEC-TG-025`
>
> 前置条件：`05-tool-integration-gateway` LLD 14 个切面已冻结
>
> 文档状态：Implementation Plan

## 1. Phase 目标

闭环 03 Runtime、06 Policy/Approval、04 Memory Knowledge、02 Ticket/Workflow 相关契约。

## 2. 范围

包含：

- 本 Phase 范围内 specs 的设计、代码、migration、测试和 traceability；
- Tool Gateway 自有 aggregate、API、event、outbox、connector 或 worker 能力；
- 与 02/03/04/06 的契约闭环校验。

不包含：

- Ticket Workflow 主状态机重设计；
- Agent Runtime Workflow state 迁移；
- Memory active long-term write；
- Policy 规则所有权迁移；
- 跨 domain 分布式事务。

## 3. Specs

| 顺序 | SPEC | 名称 | 主要 LLD 映射 |
|---|---|---|---|
| 1 | `SPEC-TG-022` | 03 Agent Runtime Tool Contract | 05-api-contracts, 06-event-contracts, 14-testing-strategy |
| 2 | `SPEC-TG-023` | 06 Policy Approval Contract | 06-event-contracts, 02-business-invariants |
| 3 | `SPEC-TG-024` | 04 Memory Evidence Contract | 06-event-contracts, 11-security |
| 4 | `SPEC-TG-025` | 02 Ticket Workflow Traceability Contract | 06-event-contracts, 02-business-invariants |

## 4. 强制约束

- Agent 不能直接调用 Tool；
- Tool execution 不能直接推进 Ticket/Workflow state；
- 所有外部副作用必须幂等、可审计、可恢复；
- 高风险 capability 必须经过 06 approval；
- secret/raw output 不能泄漏到事件、日志或 memory。

## 5. 退出条件

- 本 Phase 所有 spec 子目录存在，且中英文文档完整；
- 每个 spec 都有验收标准和测试计划；
- 对应 LLD 章节没有未覆盖的关键规则；
- 与相关上游/下游域的契约在测试计划中可验证。
