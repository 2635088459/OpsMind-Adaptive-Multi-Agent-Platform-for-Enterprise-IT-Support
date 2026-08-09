# SPEC-TW-036 持久化设计

## 参考 Migration

`V036__step_up_authentication_hardening.sql`

## 建议持久化

- policy decision 可以写入 `ticket.audit_records` 或专用 security/audit 表；
- required audit 与业务读取/命令的事务边界必须明确；
- secret detection 不持久化原文 secret；
- step-up proof 只保存 proof id、method、verifiedAt、expiresAt，不保存认证材料。

## Outbox

Phase 09 默认不产生 Ticket lifecycle outbox event。若需要跨 domain 发布，必须使用脱敏 payload。
