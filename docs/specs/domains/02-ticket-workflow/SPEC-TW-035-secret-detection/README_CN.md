# SPEC-TW-035 — Secret Detection（密钥检测）

## 1. 目标

阻止 secret-like 内容进入 message、reason、audit free-text 和 outbox payload。

## 2. 范围

包含：

- policy evaluation/application hook；
- API、application service、domain policy、audit/metric；
- 与 Phase 01～08 已有 endpoint 的集成；
- `security.secret-detected` 内部审计/安全记录。

不包含：

- 新增 Ticket 生命周期主状态；
- 替代 Keycloak/OAuth2 基础认证；
- 跨 domain 数据修复。

## 3. 核心规则

- 被判定为 secret-like 的 free-text 必须被拒绝、脱敏记录 metric，并且不得持久化原文。
- 拒绝路径不得产生业务成功 event；
- 审计和 telemetry 不得包含 secret、token、raw credential 或高基数字段；
- policy decision 必须可测试、可追踪、可观测。
