# SPEC-MK-007 Test Plan

## 测试类型

- Unit：domain state/rule/score/redaction。
- Application：service command 正向、冲突、幂等。
- Integration：PostgreSQL repository/migration，必要时 pgvector/RabbitMQ。
- Contract：02/03 event 或 API 兼容。
- Security：PII/secret/ACL/classification。

## 必测场景

- duplicate command/event 不产生重复状态。
- invalid payload 被拒绝或进入 poison。
- access denied 不泄漏数据。
- degraded mode 不伪造 evidence。
