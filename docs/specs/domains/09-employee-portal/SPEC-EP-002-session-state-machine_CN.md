# SPEC-EP-002 — Session State Machine（会话状态机）

> Domain: `09-employee-portal` | Phase: 01 — 登录与会话 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-002`。

## 2. 目标
实现初次登录之外的完整会话生命周期：临近过期时的静默刷新，以及刷新失败时正确迁移到 `SESSION_EXPIRED`。

## 3. 设计依据
`03-state-machine` §3.3（完整的 `AUTHENTICATED ⇄ TOKEN_REFRESHING ⇄ SESSION_EXPIRED` 状态机）。

## 4. Actor
已登录员工，贯穿整个会话生命周期。

## 5. 范围
后台 token 过期检测、静默刷新，以及 `SESSION_EXPIRED` 迁移+重新登录提示。

## 6. 非目标
不实现 BI-EP-006 的草稿保存行为——那是 SPEC-EP-003，叠加在本状态机的 `SESSION_EXPIRED` 迁移之上。

## 7. 前置条件
`AUTHENTICATED`。

## 8. 输入
无（后台、由时间驱动）。

## 9. 详细行为
临近过期时尝试静默刷新；成功则保持 `AUTHENTICATED`；失败（被撤销、refresh token 过期）迁移到 `SESSION_EXPIRED`。

## 10. 交互状态迁移
`AUTHENTICATED → TOKEN_REFRESHING → AUTHENTICATED | SESSION_EXPIRED`，与 `03-state-machine` §3.3 声明的完全一致。

## 11. 业务不变量
无直接相关；本 spec 是 BI-EP-006（SPEC-EP-003）依赖的机制。

## 12. 幂等策略
刷新尝试天然幂等（重复尝试刷新除了拿到一个新 token 之外没有额外副作用）。

## 13. 消费/依赖的契约
与 SPEC-EP-001 相同的真实 Keycloak 会话机制。

## 14. 安全
不引入新 scope。刷新过程透明发生；refresh token 从不暴露给前端 JS。

## 15. 可观测性
刷新成功/失败率的指标是一个有用的未来补充（`12-observability-and-audit` §4），但不是本 spec 完成定义的必需项。

## 16. 错误场景
员工正在交互过程中刷新失败——这次交互不会被静默丢弃；通过 SPEC-EP-003 自己的草稿保存路径处理。

## 17. 验收场景
临近过期的会话被无感刷新；真正被撤销的会话正确到达 `SESSION_EXPIRED`。

## 18. 先写测试
针对 `03-state-machine` §3.3 每一个状态迁移的组件/单元测试；针对真实 Keycloak realm 模拟真实 token 临近过期的集成测试。

## 19. 完成定义
会话状态机的所有迁移都有测试覆盖，且在真实 Keycloak realm 上通过。
