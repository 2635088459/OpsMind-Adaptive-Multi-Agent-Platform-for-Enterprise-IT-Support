# SPEC-TW-036 — Step-up Authentication（增强认证）

## 1. 目标

对高风险 Ticket command 强制 step-up proof，避免普通会话直接执行危险操作。

## 2. 范围

包含：

- policy evaluation/application hook；
- API、application service、domain policy、audit/metric；
- 与 Phase 01～08 已有 endpoint 的集成；
- `security.step-up-verified` 内部审计/安全记录。

不包含：

- 新增 Ticket 生命周期主状态；
- 替代 Keycloak/OAuth2 基础认证；
- 跨 domain 数据修复。

## 3. 核心规则

- 缺少有效 step-up proof 的高风险 command 必须在业务 mutation 前拒绝。
- 拒绝路径不得产生业务成功 event；
- 审计和 telemetry 不得包含 secret、token、raw credential 或高基数字段；
- policy decision 必须可测试、可追踪、可观测。
