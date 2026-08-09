# SPEC-TW-040 领域规则

- Phase 10 recovery 不新增业务 happy path。
- compensation 必须选择已定义 action，不允许任意 SQL/任意状态修改。
- recovery 必须通过专门 command/use case 执行，controller、scheduler、consumer 不得直接改 entity。
- 所有 action 都必须绑定 case/attempt 或 source event。
- 不能以修复为理由跳过 authorization、audit、idempotency 或 outbox。
