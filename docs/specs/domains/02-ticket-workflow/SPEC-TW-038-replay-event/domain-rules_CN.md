# SPEC-TW-038 领域规则

- Phase 10 recovery 不新增业务 happy path。
- replay 必须以 original event id 和 replay attempt id 双重幂等。
- recovery 必须通过专门 command/use case 执行，controller、scheduler、consumer 不得直接改 entity。
- 所有 action 都必须绑定 case/attempt 或 source event。
- 不能以修复为理由跳过 authorization、audit、idempotency 或 outbox。
