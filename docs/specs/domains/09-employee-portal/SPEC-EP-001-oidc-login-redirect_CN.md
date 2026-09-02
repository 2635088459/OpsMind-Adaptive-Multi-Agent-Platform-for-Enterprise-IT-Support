# SPEC-EP-001 — OIDC Login Redirect（OIDC 登录跳转）

> Domain: `09-employee-portal` | Phase: 01 — 登录与会话 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-001`，实现 `UC-EP-01` 的登录前置条件。

## 2. 目标
让未登录员工经过真实 Keycloak Authorization Code + PKCE 流程，回到门户时处于 `AUTHENTICATED`。

## 3. 设计依据
`01-domain-model` §"UserSession"；`03-state-machine` §3.3；`11-security-and-authorization` §1。

## 4. Actor
未登录员工。

## 5. 范围
登录发起按钮/跳转、回调处理、以及通过 `OPSMIND_SESSION` cookie 是否存在来判定进入 `AUTHENTICATED`。

## 6. 非目标
不实现 OIDC 本身（复用 `01-user-access-authentication` 已经真实、已经验证过的流程）——具体真实跑通的往返细节见 `project-level-integration-verification` memory。

## 7. 前置条件
无——这是未登录访客的入口。

## 8. 输入
无（一次简单的跳转，没有请求体）。

## 9. 详细行为
`GET /oauth2/authorization/opsmind` → 302 到 Keycloak → 真实凭据 → 302 回调 → `Set-Cookie: OPSMIND_SESSION` → 跳转回门户首页。

## 10. 交互状态迁移
`UNAUTHENTICATED → LOGIN_IN_PROGRESS → AUTHENTICATED`（失败则回到 `UNAUTHENTICATED`），遵循 `03-state-machine` §3.3。

## 11. 业务不变量
BI-EP-001~007 都不直接适用于登录本身；BI-EP-006 从下一个 spec 起才开始起作用。

## 12. 幂等策略
不适用——这是基于 GET 的跳转流程，不是有副作用的命令。

## 13. 消费/依赖的契约
`01-user-access-authentication` 已经真实存在、已经现场验证过的 OIDC 会话机制。

## 14. 安全
不引入新 scope。会话 cookie 是 `HttpOnly`/`Secure`/`SameSite=Lax`；前端 JS 从不读取其值（`11-security-and-authorization` §1）。

## 15. 可观测性
除平台通用的 trace 透传外（`12-observability-and-audit`），本 spec 无特别之处。

## 16. 错误场景
Keycloak 侧登录失败——回到 `UNAUTHENTICATED` 并展示错误提示；不设置会话 cookie。

## 17. 验收场景
真实浏览器对着真实 Keycloak realm 完整走完跳转往返，落在 `AUTHENTICATED`——复用 2026-09-01 已经现场验证过的那套流程。

## 18. 先写测试
`14-testing-strategy` §3.2 里 E2E-EP-01 的登录部分，在跳转 UI 本身之前先写。

## 19. 完成定义
真实登录往返在真实 docker-compose 栈上跑通；本 spec 不使用任何 mock（与本 domain 大多数其他 spec 不同）。
