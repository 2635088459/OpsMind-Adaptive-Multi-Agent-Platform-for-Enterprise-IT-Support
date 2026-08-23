# Persistence — SPEC-TG-028

## 持久化要求

本 spec 的实现应优先复用 05 LLD 中定义的表：

- `tool_requests`
- `tool_executions`
- `tool_connectors`
- `tool_results`
- `credential_bindings`
- `tool_audit_records`
- `outbox_events`
- `processed_events`

## 数据规则

- 所有 command 必须保存 payload hash 或版本条件，支持幂等/冲突检测；
- 所有 final outcome 必须可从数据库恢复并重新发布 outbox；
- secret value 不得保存；
- raw output 只能保存 storage reference 和 classification metadata；
- audit record 与状态迁移必须在同一事务内写入。
