# Persistence — SPEC-PG-028

## 持久化要求

本 spec 的实现应优先复用 06 LLD 中定义的表：

- `policies`
- `policy_versions`
- `policy_decisions`
- `approval_requests`
- `approval_decisions`
- `governance_audit_records`
- `outbox_events`
- `processed_events`

## 数据规则

- 所有 command 必须保存 payload/input hash 或版本条件；
- 所有 final outcome 必须可从数据库恢复并重新发布 outbox；
- sensitive raw input 默认不保存；
- audit record 与状态迁移必须在同一事务内写入；
- published policy version 和 final approval decision 不可变。
