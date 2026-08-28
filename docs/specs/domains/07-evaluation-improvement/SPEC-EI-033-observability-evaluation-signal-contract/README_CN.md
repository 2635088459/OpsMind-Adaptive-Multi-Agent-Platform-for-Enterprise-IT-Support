# SPEC-EI-033 — Observability Evaluation Signal Contract

> 领域：Evaluation Improvement
>
> Phase：07 — 跨域契约闭环
>
> 服务：`evaluation-improvement-service`
>
> 技术栈：Python 3.12 / FastAPI / Pydantic / SQLAlchemy / Alembic / PostgreSQL / RabbitMQ / LangSmith SDK / OpenTelemetry / pytest
>
> LLD 映射：`12-observability, 14-testing-strategy`
>
> 文档状态：Spec Planning

## 1. 目标

锁定 07 输出给 08 的 metrics/logs/traces/alerts/dashboard semantic fields。

## 2. 范围

包含：

- 本 spec 所需 domain/application/infrastructure/interface 设计；
- 对应 persistence、API/event contract、测试和验收标准；
- 与 Evaluation Improvement LLD 的边界一致性；
- 与 01/02/03/04/05/06/08 的相关契约校验。

不包含：

- 生产 Agent/Prompt 直接修改；Ticket/Workflow state 直接迁移；Tool 执行或 Connector 管理；Memory 内容写入；Policy/Approval 规则所有权迁移；跨 domain 分布式事务。

## 3. 核心规则

- 07 只能输出 evaluation facts、gate decision、candidate proposal 和 rollback recommendation，不能直接执行业务副作用。
- 所有评估事实必须绑定 dataset version、target version、grader version、policy version/input hash 和 correlation id。
- 安全门禁必须由 deterministic grader 判定；LLM Judge 只能用于质量类辅助评分。
- candidate promotion 必须经过 benchmark、release gate、06 approval、Canary 和 rollback guard。
- 所有状态迁移必须同事务写 audit/outbox；所有消费事件必须 processed-event 去重。
- 本 spec 聚焦 `observability signal contract`，不得扩大到相邻 phase 的实现范围。
