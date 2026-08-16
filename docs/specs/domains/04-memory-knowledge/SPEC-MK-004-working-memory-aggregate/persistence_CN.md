# SPEC-MK-004 Persistence

## 持久化要求

- 使用 PostgreSQL schema `memory`。
- 需要 version、status、created_at、updated_at。
- 需要唯一键支持幂等。
- 需要 migration 和 repository 测试。

## 相关表

- `memory.processed_events`：消费事件去重。
- `memory.outbox_events`：发布事件。
- 具体业务表按本 spec 的 domain model 增补。
