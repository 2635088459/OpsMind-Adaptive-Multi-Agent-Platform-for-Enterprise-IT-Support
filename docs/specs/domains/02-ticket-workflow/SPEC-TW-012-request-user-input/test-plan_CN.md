# SPEC-TW-012 — TDD 测试计划

覆盖：

- `IN_PROGRESS -> WAITING_FOR_USER` 成功；
- 非 `IN_PROGRESS` 拒绝；
- 缺失负责人拒绝；
- open request 已存在拒绝；
- prompt 校验和 secret filtering；
- queue authorization；
- expected version conflict；
- idempotency replay/conflict；
- PostgreSQL unique partial index；
- Ticket/request/history/timeline/audit/outbox 同事务；
- `ticket.user-input-requested.v1` contract；
- E2E：assign -> start -> request user input。

退出条件：所有单元、集成、契约和 E2E 测试 deterministic 通过。
