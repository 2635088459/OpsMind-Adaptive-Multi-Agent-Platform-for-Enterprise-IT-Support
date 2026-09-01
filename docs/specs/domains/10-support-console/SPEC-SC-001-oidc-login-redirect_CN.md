# SPEC-SC-001 — OIDC Login Redirect（OIDC 登录跳转）

> Domain: `10-support-console` | Phase: 01 — 已认证会话基础 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-001`，是 `SPEC-EP-001` 在 support-console 里的对应版本——同样的真实 OIDC/Keycloak 流程底层，但应用/client 注册不同。

## 2. 目标
让支持坐席/管理员通过已有、真实的 OIDC 流程（属于 domain 01，`user-access-authentication-service`）完成认证，带着一个有效会话进入控制台。

## 3. 设计依据
`01-domain-model` §"UserSession"；`05-api-contracts` §1；domain 01 自己的 OIDC 契约（真实、已实现）。

## 4. Actor
未认证状态下打开控制台的支持坐席或管理员。

## 5. 范围
针对 support-console 自己注册的 OIDC client，实现跳转到 Keycloak、回调处理、会话建立。

## 6. 非目标
任何新的后端鉴权逻辑（domain 01 已完全实现）——本 spec 纯粹是控制台侧对一个已真实存在能力的接线，使用与员工门户自身注册不同的 client ID。

## 7. 前置条件
无——这是控制台的入口点。

## 8. 输入
无（由跳转发起），随后是 OIDC 回调的授权码。

## 9. 详细行为
未认证访问 → 跳转到 Keycloak（support-console client）→ 回调 → token 交换 → 会话建立 → 落地到队列视图（SPEC-SC-003）。

## 10. 交互状态迁移
与本 domain `03-state-machine` 中的会话生命周期模式与 `SPEC-EP-001` 一致。

## 11. 业务不变量
BI-SC（会话相关不变量，与 BI-EP-001 精神一致）——没有有效会话，任何控制台界面都不渲染。

## 12. 幂等策略
不适用——是一个浏览器跳转流程，不是一个有副作用的 API 调用。

## 13. 消费/依赖的契约
domain 01 真实的 OIDC 端点，使用 support-console 独立注册的 client。

## 14. 安全
需要 support-console 自己的 Keycloak client 注册，带有适合坐席/管理员的 realm 角色（与员工门户的 client 不同）。

## 15. 可观测性
登录成功/失败的客户端事件，与 SPEC-EP-001 自己的约定一致。

## 16. 错误场景
授权码无效/过期、同意被拒绝——处理模式与 SPEC-EP-001 一致。

## 17. 验收场景
一个未认证的坐席被跳转、完成登录后，带着一个有效会话落地到队列视图。

## 18. 先写测试
针对真实 Keycloak 测试 realm 的 E2E 测试（与 SPEC-EP-001 自己的测试方式一致），因为这是真实基础设施，不是待建契约。

## 19. 完成定义
登录流程针对真实 Keycloak 实例端到端验证通过；会话在页面刷新后正确保持。
