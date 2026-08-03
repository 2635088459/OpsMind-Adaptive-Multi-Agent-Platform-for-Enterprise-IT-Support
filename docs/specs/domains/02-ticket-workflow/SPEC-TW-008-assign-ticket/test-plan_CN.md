# SPEC-TW-008 — TDD 测试计划

## 1. TDD 顺序

### Step 1 — Aggregate 单元测试

先为合法与非法的 Assign、Reassign、Unassign 转换编写失败测试，再实现 Aggregate Methods。

### Step 2 — 资格与授权测试

测试 Tenant、Active State、Support Role、Queue Membership、Actor Role、Operation Permission 与 Queue Scope。

### Step 3 — Handler 测试

测试编排顺序、依赖失败、错误映射、重放行为与响应映射。

### Step 4 — Repository 集成测试

使用 PostgreSQL/Testcontainers 验证乐观更新、只追加历史、索引、约束与事务回滚。

### Step 5 — API 契约测试

验证 Headers、Payload 约束、Problem Details、Response `ETag` 和 OpenAPI 示例。

### Step 6 — Event 契约测试

序列化三类事件并使用 AsyncAPI 校验，同时检查版本、顺序和隐私规则。

### Step 7 — 并发与端到端测试

让多个命令竞争同一版本，重试请求，注入持久化失败，并验证完整 Timeline/Audit/Outbox 结果。

## 2. 最低测试矩阵

| ID | 层级 | 场景 | 预期 |
|---|---|---|---|
| UT-01 | Domain | Assign TRIAGED/无负责人 | ASSIGNED、设置负责人、版本 +1 |
| UT-02 | Domain | Assign 状态错误 | `INVALID_TICKET_STATE` |
| UT-03 | Domain | Assign 已有负责人 | `TICKET_ALREADY_ASSIGNED` |
| UT-04 | Domain | Reassign ASSIGNED | 负责人改变，状态不变 |
| UT-05 | Domain | Reassign IN_PROGRESS | 负责人改变，保持 IN_PROGRESS |
| UT-06 | Domain | Reassign 给同一用户 | 校验/冲突，无变更 |
| UT-07 | Domain | Unassign ASSIGNED | TRIAGED，清除负责人 |
| UT-08 | Domain | Unassign IN_PROGRESS/waiting | `INVALID_TICKET_STATE` |
| AP-01 | Application | 合格 active queue member | 调用 Aggregate |
| AP-02 | Application | Assignee 不存在 | `ASSIGNEE_NOT_FOUND` |
| AP-03 | Application | Assignee inactive | `ASSIGNEE_INACTIVE` |
| AP-04 | Application | 无 Support 能力 | `ASSIGNEE_NOT_SUPPORT_AGENT` |
| AP-05 | Application | 不属于 Queue | `ASSIGNEE_NOT_IN_QUEUE` |
| SEC-01 | Security | Requester Actor | `403`，无写入 |
| SEC-02 | Security | Actor 无 Queue 权限 | `403`，无写入 |
| SEC-03 | Security | 跨 Tenant Ticket/Assignee | 不泄露信息 |
| DB-01 | Integration | Assign 成功 | 所有要求记录提交 |
| DB-02 | Integration | Reassign 成功 | 不写 Status History |
| DB-03 | Integration | Unassign 成功 | 提交 Status History |
| DB-04 | Integration | Outbox 写入失败 | 全部回滚 |
| DB-05 | Integration | History 写入失败 | 全部回滚 |
| CC-01 | Concurrency | 同版本两个命令 | 一个成功，一个冲突 |
| ID-01 | Idempotency | 相同请求重放 | 原始响应，仅一次副作用 |
| ID-02 | Idempotency | 同 Key 新 Fingerprint | `IDEMPOTENCY_KEY_REUSED` |
| API-01 | Contract | 缺少 If-Match | 按共享 API Policy 返回 `400`/`428` |
| API-02 | Contract | UUID/Reason 非法 | `400 VALIDATION_ERROR` |
| EVT-01 | Contract | 三个事件 Schema | AsyncAPI 校验成功 |
| E2E-01 | E2E | TRIAGED → assign → reassign → unassign | 完整追踪且最终状态正确 |

## 3. 原子性断言

注入失败后必须确认以下内容均未改变：

- Ticket Assignee、Status 与 Version；
- Assignment/Status History 数量；
- Timeline 与 Audit 数量；
- Outbox 数量；
- 最终 Idempotency Response。

## 4. 安全断言

- 每次 Read/Update 都包含 Tenant Predicate；
- 客户端传入的 Actor/Tenant 字段被忽略或拒绝；
- Error 不泄露其他 Tenant 的 User 或 Ticket；
- Logs 不包含 Authorization Header 和完整 Idempotency Key；
- Timeline 内容对 Requester 安全。

## 5. 事件断言

- Event Type 与 v1 Schema 完全匹配；
- Event ID 是唯一 UUID，时间使用 UTC；
- Aggregate ID 等于 Ticket ID；
- Aggregate Version 等于已保存版本；
- Reassign 包含新旧负责人且状态未改变；
- Unassign 包含原负责人且不虚构新负责人；
- 不包含秘密或完整 Identity Profile。

## 6. 可观测性断言

验证以下 Counters：

```text
ticket_assignment_commands_total{operation,outcome}
ticket_assignment_conflicts_total{type}
ticket_assignment_duration_seconds{operation}
```

Metric Labels 不得包含 Ticket ID、User ID、Reason 或 Idempotency Key。

## 7. 退出标准

- 所有测试稳定通过；
- Concurrency、Authorization、Rollback 与 Contract 测试均不可跳过；
- Migration 可运行于兼容 Phase 01–007 的 Schema；
- OpenAPI 与 AsyncAPI 通过验证；
- 覆盖每个状态和稳定错误码；
- E2E 证明 Ticket、History、Timeline、Audit 与 Outbox 的完整可追踪性。
