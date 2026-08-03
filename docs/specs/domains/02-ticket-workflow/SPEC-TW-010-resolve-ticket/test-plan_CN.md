# SPEC-TW-010 — TDD 测试计划

## 1. TDD 顺序

### Step 1 — Aggregate Unit Tests

先写失败测试覆盖 `IN_PROGRESS -> RESOLVED`、非法状态、缺失负责人、非法 resolution code 和 summary 规则。

### Step 2 — Resolution Cycle Tests

测试当前 cycle 存在、未完成、属于同一 Ticket，并在 resolve 成功时完成。

### Step 3 — Authorization Tests

测试 actor scope、support team queue access、Requester 拒绝和 Automation Agent service identity。

### Step 4 — Handler Tests

测试 orchestration order、幂等 replay、错误映射、版本检查、响应映射和 outbox mapping。

### Step 5 — Repository Integration Tests

用 PostgreSQL/Testcontainers 验证条件更新、CHECK constraint、cycle completion、history append-only、metadata 清理和事务回滚。

### Step 6 — API Contract Tests

验证 headers、payload constraints、Problem Details、`ETag` 和 OpenAPI 示例。

### Step 7 — Event Contract Tests

序列化 `ticket.resolved.v1`，用 AsyncAPI/schema 校验版本、ordering 和 privacy 规则。

### Step 8 — Concurrency and E2E Tests

并发 race 同一版本，重放 idempotency key，注入持久化失败，并验证完整 timeline/audit/outbox。

## 2. 最小测试矩阵

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| UT-01 | Domain | `IN_PROGRESS -> RESOLVED` | status changed, version +1 |
| UT-02 | Domain | `ASSIGNED -> RESOLVED` | `INVALID_STATUS_TRANSITION` |
| UT-03 | Domain | no assignee | `TICKET_NOT_ASSIGNED` |
| UT-04 | Domain | invalid resolution code | rejected |
| UT-05 | Domain | blank/short summary | rejected |
| AP-01 | Application | happy resolve | all ports invoked once |
| AP-02 | Application | missing scope | `FORBIDDEN`, no writes |
| AP-03 | Application | queue denied | `QUEUE_ACCESS_DENIED`, no writes |
| AP-04 | Application | stale version | `VERSION_CONFLICT`, no writes |
| AP-05 | Application | missing cycle | `RESOLUTION_CYCLE_NOT_FOUND` |
| AP-06 | Application | completed cycle | `RESOLUTION_CYCLE_ALREADY_COMPLETED` |
| DB-01 | Integration | successful resolve | ticket/cycle/history/audit/outbox committed |
| DB-02 | Integration | metadata cleanup | waiting metadata cleared |
| DB-03 | Integration | rollback on cycle failure | no partial writes |
| DB-04 | Integration | rollback on outbox failure | no partial writes |
| CC-01 | Concurrency | same version, two resolves | one success, one conflict |
| ID-01 | Idempotency | identical replay | original response, one side effect |
| ID-02 | Idempotency | same key, different body | `IDEMPOTENCY_KEY_REUSED` |
| API-01 | Contract | missing If-Match | `428 PRECONDITION_REQUIRED` |
| API-02 | Contract | invalid code/summary | stable validation error |
| EVT-01 | Contract | resolved schema | valid |
| E2E-01 | E2E | assign -> start -> resolve | correct final state and trace |

## 3. Atomicity Assertions

注入失败后断言以下内容不变：

- Ticket status、resolution fields、waiting metadata 和 version；
- resolution cycle status；
- status history count；
- timeline 和 audit count；
- outbox count；
- finalized idempotency response。

## 4. Security Assertions

- actor/body spoofing 被忽略或拒绝；
- Requester 无法解决工单；
- queue scope predicate 使用 Ticket triage 时确定的队列；
- 错误不泄露其他队列细节；
- 日志不包含 Authorization header、idempotency key、secret 或完整 summary。

## 5. Event Assertions

- event type 精确为 `ticket.resolved.v1`；
- aggregate version 等于 Ticket 存储版本；
- previous/new status 与 history 一致；
- resolution cycle ID 与完成的 cycle 一致；
- 无 secret、raw claims 或完整身份资料。

## 6. Observability Assertions

验证 counters/timers：

```text
ticket_resolution_commands_total{resolution_code,outcome}
ticket_resolution_conflicts_total{type}
ticket_resolution_duration_seconds
```

Metric label 不包含 ticket ID、user ID、summary 或 idempotency key。

## 7. Exit Criteria

- 所有测试 deterministic 且通过；
- 无跳过 authorization、rollback、concurrency 或 contract 测试；
- migration 可在 Phase 01-009 schema 上执行；
- OpenAPI 和 AsyncAPI 可校验；
- E2E 证明 ticket、cycle、history、timeline、audit 和 outbox 可追踪。
