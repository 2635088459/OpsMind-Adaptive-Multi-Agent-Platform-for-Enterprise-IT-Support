# SPEC-PG-006 — Decision Evaluate API

> 领域：Policy Approval Governance
>
> Phase：01 — Policy 模型与决策引擎
>
> 服务：`policy-approval-governance-service`
>
> LLD 映射：`05-api-contracts, 09-concurrency-and-idempotency`
>
> 文档状态：Spec Planning

## 1. 目标

实现 `/policy-decisions:evaluate` API，返回 allow/deny/approval-required/constraints。

## 2. 范围

包含：

- 本 spec 所需 domain/application/infrastructure/interface 设计；
- 对应 persistence、API/event contract、测试和验收标准；
- 与 Policy Approval Governance LLD 的边界一致性。

不包含：

- Tool 直接执行；Ticket/Workflow state 直接修改；Memory 内容写入；伪造审批；绕过职责分离；跨 domain 分布式事务。

## 3. 核心规则

- 06 只能输出治理事实；decision 必须绑定 policy version/input hash/reason codes/constraints；approval final decision 必须幂等且唯一；所有治理状态迁移必须同事务写 audit/outbox。
- 本 spec 的实现不得让 06 拥有 Ticket、Workflow、Tool Execution 或 Memory state；
- 本 spec 产生的事实必须可追溯 source domain、source request、actor、policy version 和 correlation id。
