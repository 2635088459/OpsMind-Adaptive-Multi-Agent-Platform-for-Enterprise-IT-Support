# SPEC-TW-039 测试计划

## 单元测试

- recovery precondition allow/deny；
- duplicate idempotency replay；
- stale source reference 拒绝；
- audit payload redaction。

## 集成测试

- transaction/outbox/idempotency 一致；
- DLQ/replay/correction/compensation/integrity repair 场景按本 SPEC 覆盖；
- crash-window 或 partial failure 不产生静默成功。

## Release Gate

- golden path、recovery path、security hardening、performance smoke 均通过。
