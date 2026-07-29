# SPEC-TW-007 — 测试计划与实现清单

## 1. TDD 顺序

### Step 1 — 领域单元测试

先编写失败测试：

- `OPEN → TRIAGED`；
- 从每个非 `OPEN` 状态执行时都被拒绝；
- Category、Priority、Queue、Actor 和 Time 必填；
- Subcategory 可选；
- Version 只增加一次；
- 生成的领域结果只包含获准的 Before/After 值。

### Step 2 — Validation 与 Authorization 测试

- Category 不存在/未启用；
- Subcategory 不存在/未启用/Parent 错误；
- Queue 不存在/未启用/跨 Tenant；
- Requester 禁止；
- Support Agent 按 Queue 允许/拒绝；
- Support Lead Scope；
- Automation Agent Explicit Grant；
- UUID、Enum、Reason Length 和 Unknown Body Field 错误。

### Step 3 — Repository 与 Migration 测试

- 从 Phase 02 Snapshot 迁移；
- Ticket Field Mapping；
- Tenant 范围的 Optimistic Update；
- Status History Append；
- Timeline/Audit/Outbox/Idempotency Insert；
- Persistence Constraint 拒绝不一致的直接写入。

### Step 4 — Handler 集成测试

- 完整成功事务；
- 不带 Subcategory 的成功事务；
- 每个稳定错误映射；
- 模拟每一种附属写入失败后的事务回滚；
- 所有记录复用捕获的 Timestamp；
- Actor 来自 Authentication Context。

### Step 5 — API 契约测试

- Header 与 Path Validation；
- `200` Response Schema 与 `ETag`；
- RFC 9457 风格 Error Envelope；
- 缺少 `If-Match` 返回 `428`；
- ETag 过期返回 `412`；
- OpenAPI Request/Response Validation。

### Step 6 — 幂等与并发测试

- 相同 Key/相同 Hash 重放；
- 相同 Key/不同 Hash 冲突；
- 两个并发命令都使用 Version 7：只能一个 `200`，另一个 `412`；
- 成功后重放不重复创建 Status History、Timeline、Audit 或 Outbox；
- 在变更前 Validation 失败后可继续使用同一 Key。

### Step 7 — 事件契约测试

- 精确事件名 `ticket.triaged.v1` 与 v1 Schema；
- UTC Timestamp；
- Partition/Aggregate ID 为 Ticket ID；
- Nullable Subcategory；
- 不包含 Secret/Requester Message；
- AsyncAPI Schema 可验证序列化事件。

## 2. 最小测试矩阵

| ID | Layer | 场景 | 预期 |
|---|---|---|---|
| UT-01 | Domain | 有效 OPEN Ticket | TRIAGED，Version + 1 |
| UT-02 | Domain | Ticket 已是 TRIAGED | `INVALID_TICKET_STATE` |
| UT-03 | Domain | 未提供可选 Subcategory | 成功且为 null |
| AU-01 | Auth | Requester | 403 |
| AU-02 | Auth | Agent 无 Queue Access | 403 |
| VA-01 | Validation | Category 未启用 | 422 |
| VA-02 | Validation | Subcategory Parent 错误 | 422 |
| VA-03 | Validation | Queue 未启用 | 422 |
| AP-01 | API | 缺少 If-Match | 428 |
| AP-02 | API | If-Match 过期 | 412 |
| ID-01 | Idempotency | 相同 Key/相同请求 | 保存的 200，无重复 |
| ID-02 | Idempotency | 相同 Key/不同请求 | 409 |
| DB-01 | Integration | Outbox Insert 失败 | 全部回滚 |
| DB-02 | Integration | 两个 Writer | 只能一个成功 |
| EV-01 | Contract | 序列化事件 | 匹配 AsyncAPI |
| SEC-01 | Security | 跨 Tenant Ticket | 无法区分的 404 |

## 3. 测试数据 Builders

提供以下 Builder/Fixture：

```text
openTicket()
activeCategory()
activeSubcategory(categoryId)
activeSupportQueue()
authorizedSupportAgent(queueId)
requester()
triageRequest()
```

单元测试使用 Fixed Clock 和 Deterministic UUID Provider。集成测试可以使用真实 UUID，但应断言关系而不是偶然顺序。

## 4. 可观测性验证

验证：

- Success 和有限 Failure Counter；
- Duration Histogram/Timer；
- Version Conflict Counter；
- Structured Log Keys；
- Correlation Context 从 HTTP 流向 Transaction 和 Outbox Publish；
- Metrics Label 不使用 Ticket ID 等高基数字段。

## 5. 安全检查

- 服务端忽略或拒绝 Actor 冒充字段；
- 所有 Read/Write 都包含 Tenant Scope；
- Error 不泄露不可访问资源；
- Log 对 Authorization 与 Secret 脱敏；
- Reason 限制长度并安全渲染；
- Service Identity 不能超出明确 Queue Grant。

## 6. 实现清单

- [ ] 先提交 Acceptance Tests
- [ ] 实现 Command 与 Handler
- [ ] 集中实现 Aggregate Rule
- [ ] 实现 Catalog 与 Queue Authorization
- [ ] 执行 Migration 并更新 Repository Mapping
- [ ] Ticket/History/Timeline/Audit/Outbox/Idempotency 原子提交
- [ ] 实现 Optimistic Locking 与 ETag
- [ ] 实现 Idempotent Replay
- [ ] OpenAPI 契约测试通过
- [ ] AsyncAPI 事件测试通过
- [ ] 回滚与并发测试通过
- [ ] 验证 Metrics、Logs 与 Traces
- [ ] 文档包含全部稳定错误
- [ ] `SPEC-TW-007` Demo 已录制或可复现

## 7. Demo 脚本

1. 通过 `SPEC-TW-001` 创建 `OPEN` Ticket。
2. 读取 Ticket 并记录 `ETag`。
3. 使用有效分类和队列数据执行分诊。
4. 展示 `TRIAGED`、Version 增加和新 `ETag`。
5. 展示 Timeline 和 Status History。
6. 展示 Pending/Published Outbox Event。
7. 使用相同 Idempotency Key 重放，证明无重复记录。
8. 使用原始过期 ETag 再请求，展示 `412 VERSION_CONFLICT`。

