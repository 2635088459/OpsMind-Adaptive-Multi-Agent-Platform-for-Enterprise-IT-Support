# SPEC-TW-033 — Support Queue Authorization（支持队列授权）

## 1. 目标

收紧 Support Queue 范围授权，使读取、队列查询和 command 准入都遵循同一 scope policy。

## 2. 范围

包含：

- policy evaluation/application hook；
- API、application service、domain policy、audit/metric；
- 与 Phase 01～08 已有 endpoint 的集成；
- `audit.authorization-denied-recorded` 内部审计/安全记录。

不包含：

- 新增 Ticket 生命周期主状态；
- 替代 Keycloak/OAuth2 基础认证；
- 跨 domain 数据修复。

## 3. 核心规则

- 任何 queue-scoped actor 只能读取或操作其授权 Support Queue 范围内的 Ticket。
- 拒绝路径不得产生业务成功 event；
- 审计和 telemetry 不得包含 secret、token、raw credential 或高基数字段；
- policy decision 必须可测试、可追踪、可观测。
