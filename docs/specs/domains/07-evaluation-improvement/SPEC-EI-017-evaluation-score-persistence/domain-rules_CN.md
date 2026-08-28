# Domain Rules — SPEC-EI-017

## 必须遵守

- 07 只能输出 evaluation facts、gate decision、candidate proposal 和 rollback recommendation，不能直接执行业务副作用。
- 所有评估事实必须绑定 dataset version、target version、grader version、policy version/input hash 和 correlation id。
- 安全门禁必须由 deterministic grader 判定；LLM Judge 只能用于质量类辅助评分。
- candidate promotion 必须经过 benchmark、release gate、06 approval、Canary 和 rollback guard。
- 所有状态迁移必须同事务写 audit/outbox；所有消费事件必须 processed-event 去重。
- 本 spec 的所有 domain rule 必须可由 unit test 或 architecture test 验证。
- 与 `07-data-model, 08-transaction-and-outbox` 相关的规则必须进入 traceability。

## 禁止

- 生产 Agent/Prompt 直接修改；Ticket/Workflow state 直接迁移；Tool 执行或 Connector 管理；Memory 内容写入；Policy/Approval 规则所有权迁移；跨 domain 分布式事务。
- 静默改写 published dataset、final run、final report、final candidate 或 audit record。
- 在缺失 source linkage、version、hash 或 correlation id 时产出 passed gate。
