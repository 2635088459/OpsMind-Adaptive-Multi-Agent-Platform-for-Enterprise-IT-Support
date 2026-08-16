# SPEC-MK-025 — Retrieval access control 与 ACL

> 领域：Memory Knowledge
>
> Phase：07 — Security 与 Governance
>
> 服务：`memory-knowledge-service`
>
> LLD 映射：`11-security, 02-business-invariants`
>
> 文档状态：Spec Planning

## 1. 目标

Implement access scope, document ACL, memory classification, role filters.

## 2. 范围

包含：

- 本 spec 所需 domain/application/infrastructure/interface 设计；
- 对应 persistence、API/event contract、测试和验收标准；
- 与 02 Ticket Workflow、03 Agent Runtime 的边界一致性。

不包含：

- 02 Ticket state 修改；
- 03 Workflow state 修改；
- Tool 直接执行；
- 未经验证的 active memory 写入；
- 跨 domain 分布式事务。

## 3. 核心规则

- Search cannot cross tenant/role/classification boundaries.
- Memory result 必须可追溯 provenance；
- 敏感数据必须脱敏或拒绝；
- 需要写状态的命令必须具备幂等或版本保护；
- 事件消费必须 processed-event 去重；
- 事件发布必须经过 Memory outbox。
