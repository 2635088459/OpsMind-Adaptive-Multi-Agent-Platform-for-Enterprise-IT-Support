# SPEC-TW-011 — TDD 测试计划

## 1. TDD 顺序

1. Aggregate unit tests：close/reopen 状态转换、不变量和字段清理。
2. Resolution cycle tests：close 当前 cycle、reopen 创建新 cycle、旧 cycle 保留快照。
3. Authorization tests：Support Lead、Support Agent、Requester、Automation Agent、queue scope。
4. Handler tests：幂等、版本、错误映射、response、outbox mapping。
5. Repository integration tests：PostgreSQL 条件更新、CHECK、事务回滚。
6. API contract tests：headers、payload、Problem Details、ETag。
7. Event contract tests：`ticket.closed.v1` 和 `ticket.reopened.v1`。
8. Concurrency/E2E：resolve -> close -> reopen -> resolve -> close。

## 2. 测试矩阵

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| CL-UT-01 | Domain | `RESOLVED -> CLOSED` | success |
| CL-UT-02 | Domain | `IN_PROGRESS -> CLOSED` | invalid transition |
| CL-UT-03 | Domain | invalid close reason | rejected |
| RO-UT-01 | Domain | `RESOLVED -> IN_PROGRESS` | new cycle |
| RO-UT-02 | Domain | `CLOSED -> IN_PROGRESS` | new cycle |
| RO-UT-03 | Domain | missing reopen reason | rejected |
| RO-UT-04 | Domain | inactive assignee | warning/status |
| AP-01 | App | close happy path | all ports once |
| AP-02 | App | reopen happy path | all ports once |
| AP-03 | App | missing scope | no writes |
| AP-04 | App | stale version | no writes |
| DB-01 | Integration | close commit | ticket/cycle/history/outbox |
| DB-02 | Integration | reopen commit | old/new cycle correct |
| DB-03 | Integration | failure rollback | no partial writes |
| ID-01 | Idempotency | close replay | one side effect |
| ID-02 | Idempotency | reopen replay | one side effect |
| CC-01 | Concurrency | close vs reopen | one winner |
| EVT-01 | Contract | closed event | valid |
| EVT-02 | Contract | reopened event | valid |
| E2E-01 | E2E | full lifecycle | final trace complete |

## 3. Atomicity

故障注入后断言 Ticket、cycle、history、timeline、audit、outbox 和 idempotency response 都未部分提交。

## 4. Security

- body spoofed actor 被忽略；
- unauthorized queue 被拒绝；
- Requester 默认不能调用 support close/reopen；
- 错误不泄露其他队列存在性；
- 日志不包含 Authorization、idempotency key、secret 或完整 reason。

## 5. Exit Criteria

- 所有测试 deterministic；
- OpenAPI 和 AsyncAPI 可校验；
- migration 可在 Phase 01-010 schema 上执行；
- E2E 能证明 Phase 03 完整生命周期闭环。
