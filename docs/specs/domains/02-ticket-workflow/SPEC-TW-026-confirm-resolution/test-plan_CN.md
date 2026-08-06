# SPEC-TW-026 测试计划

## 单元测试

- state machine guard：允许 `RESOLVED`，拒绝非法状态；
- reason/actor/idempotency validation；
- duplicate command 返回首次结果；
- stale expectedVersion 返回 conflict。

## 集成测试

- command 成功后 aggregate、audit table、outbox event 同事务一致；
- outbox relay 发布 `ticket.resolution-confirmed.v1`；
- 未授权 actor 不产生 mutation；
- terminal state command 被拒绝。

## 回归测试

- 不破坏 Phase 06 tool execution；
- 不破坏 Phase 07 verification/resolution cycle；
- timeline 可以展示 Phase 8 audit event。
