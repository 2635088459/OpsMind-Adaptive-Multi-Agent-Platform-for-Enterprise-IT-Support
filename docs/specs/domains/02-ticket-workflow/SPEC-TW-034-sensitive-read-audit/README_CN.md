# SPEC-TW-034 — Sensitive Read Audit（敏感读取审计）

## 1. 目标

对敏感 Ticket 读取强制审计，并在审计写入失败时 fail-closed。

## 2. 范围

包含：

- policy evaluation/application hook；
- API、application service、domain policy、audit/metric；
- 与 Phase 01～08 已有 endpoint 的集成；
- `audit.sensitive-read-recorded` 内部审计/安全记录。

不包含：

- 新增 Ticket 生命周期主状态；
- 替代 Keycloak/OAuth2 基础认证；
- 跨 domain 数据修复。

## 3. 核心规则

- 敏感详情不得在 required audit 写入失败时返回给调用方。
- 拒绝路径不得产生业务成功 event；
- 审计和 telemetry 不得包含 secret、token、raw credential 或高基数字段；
- policy decision 必须可测试、可追踪、可观测。
