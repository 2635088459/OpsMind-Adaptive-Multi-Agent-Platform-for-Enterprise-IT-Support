# Acceptance Criteria — SPEC-TG-005

## 功能验收

- 能完成目标：实现 Runtime 创建、查询、取消 Tool Request 的 API 与 idempotency conflict 语义。
- 所有状态迁移符合 `05-api-contracts, 09-concurrency-and-idempotency` 中定义的规则。
- API、事件、persistence 或 worker 行为有明确 happy path、重复请求、失败路径。
- 不产生 Ticket/Workflow 直接状态修改。

## 安全与治理验收

- Agent 不能绕过 Gateway 或看到凭据。
- 高风险能力不能绕过 policy/approval。
- secret/raw output 不进入日志、事件、memory 或 checkpoint。
- audit record 足以解释谁请求、为什么、执行了什么、结果是什么。

## 可靠性验收

- 重复请求或重复事件不会造成重复外部副作用。
- 失败、重试、恢复路径有可测试行为。
- outbox 事件可重放且 event id 稳定。
