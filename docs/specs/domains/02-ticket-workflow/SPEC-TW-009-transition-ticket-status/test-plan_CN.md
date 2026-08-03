# SPEC-TW-009 — TDD 测试计划

## 1. TDD 顺序

### Step 1 — Aggregate Unit Tests

先写失败测试覆盖五条合法转换、所有非法转换、缺失负责人和 waiting metadata 规则。

### Step 2 — Authorization Tests

测试 actor scope、support team queue access、Requester 拒绝和 Automation Agent service identity。

### Step 3 — Handler Tests

测试 orchestration order、幂等 replay、错误映射、版本检查、响应映射和 outbox mapping。

### Step 4 — Repository Integration Tests

用 PostgreSQL/Testcontainers 验证条件更新、CHECK constraint、history append-only、metadata 清理和事务回滚。

### Step 5 — API Contract Tests

验证 headers、payload constraints、Problem Details、`ETag` 和 OpenAPI 示例。

### Step 6 — Event Contract Tests

序列化 `ticket.status-changed.v1`，用 AsyncAPI/schema 校验版本、ordering、privacy 和 waiting metadata 规则。

### Step 7 — Concurrency and E2E Tests

并发 race 同一版本，重放 idempotency key，注入持久化失败，并验证完整 timeline/audit/outbox。

## 2. 最小测试矩阵

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| UT-01 | Domain | `ASSIGNED -> IN_PROGRESS` | status changed, version +1 |
| UT-02 | Domain | `IN_PROGRESS -> WAITING_FOR_USER` | requester wait metadata set |
| UT-03 | Domain | `IN_PROGRESS -> WAITING_FOR_APPROVAL` | approval reference set |
| UT-04 | Domain | `WAITING_FOR_USER -> IN_PROGRESS` | waiting metadata cleared |
| UT-05 | Domain | `WAITING_FOR_APPROVAL -> IN_PROGRESS` | waiting metadata cleared |
| UT-06 | Domain | `TRIAGED -> IN_PROGRESS` | `INVALID_STATUS_TRANSITION` |
| UT-07 | Domain | target `RESOLVED` | `INVALID_STATUS_TRANSITION` |
| UT-08 | Domain | no assignee | `TICKET_NOT_ASSIGNED` |
| AP-01 | Application | happy start work | all ports invoked once |
| AP-02 | Application | queue denied | `QUEUE_ACCESS_DENIED`, no writes |
| AP-03 | Application | missing scope | `FORBIDDEN`, no writes |
| AP-04 | Application | stale version | `VERSION_CONFLICT`, no writes |
| DB-01 | Integration | successful transition | ticket/history/audit/outbox committed |
| DB-02 | Integration | metadata constraints | invalid rows rejected |
| DB-03 | Integration | rollback on outbox failure | no partial writes |
| DB-04 | Integration | rollback on history failure | no partial writes |
| CC-01 | Concurrency | same version, two transitions | one success, one conflict |
| ID-01 | Idempotency | identical replay | original response, one side effect |
| ID-02 | Idempotency | same key, different body | `IDEMPOTENCY_KEY_REUSED` |
| API-01 | Contract | missing If-Match | `428 PRECONDITION_REQUIRED` |
| API-02 | Contract | invalid target status | `400 VALIDATION_ERROR` or `409` per mapping |
| EVT-01 | Contract | status-changed schema | valid |
| E2E-01 | E2E | assign -> start -> wait user -> resume | correct final state and trace |

## 3. Atomicity Assertions

注入失败后断言以下内容不变：

- Ticket status、waiting metadata 和 version；
- status history count；
- timeline 和 audit count；
- outbox count；
- finalized idempotency response。

## 4. Security Assertions

- actor/tenant/body spoofing 被忽略或拒绝；
- Requester 无法推进状态；
- queue scope predicate 使用 Ticket triage 时确定的队列；
- 错误不泄露其他队列细节；
- 日志不包含 Authorization header、idempotency key 或完整 reason。

## 5. Event Assertions

- event type 精确为 `ticket.status-changed.v1`；
- aggregate version 等于 Ticket 存储版本；
- previous/new status 与 history 一致；
- waiting metadata 与目标状态一致；
- 无 secret、raw claims 或完整身份资料。

## 6. Observability Assertions

验证 counters/timers：

```text
ticket_status_transition_commands_total{transition,outcome}
ticket_status_transition_conflicts_total{type}
ticket_status_transition_duration_seconds{transition}
```

Metric label 不包含 ticket ID、user ID、reason 或 idempotency key。

## 7. Exit Criteria

- 所有测试 deterministic 且通过；
- 无跳过 authorization、rollback、concurrency 或 contract 测试；
- migration 可在 Phase 01-008 schema 上执行；
- OpenAPI 和 AsyncAPI 可校验；
- `SPEC-TW-008` reassign 回归测试覆盖 `IN_PROGRESS`；
- E2E 证明 ticket、history、timeline、audit 和 outbox 可追踪。
