# SPEC-SC-002 — Role Scope Verification（角色权限校验）

> Domain: `10-support-console` | Phase: 01 — 已认证会话基础 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-002`，对 SPEC-SC-001 的强化，加入真实的基于角色 UI 门控。

## 2. 目标
只展示已登录坐席/管理员实际被授权的操作，读取 domain 01 签发的会话 token 中真实的角色/scope——同时如实反映已知的后端缺口：`policy-approval-governance` 的 `ApprovalController` 目前没有细粒度授权（遵循 `11-security-and-authorization` §2）。

## 3. 设计依据
`01-domain-model` §"UserSession"；`11-security-and-authorization` §1-2。

## 4. Actor
已登录的支持坐席或管理员。

## 5. 范围
读取 token 中的角色/scope，并据此有条件地渲染 UI 操作（例如仅管理员可见的 reconciliation 触发器 vs. 坐席的队列操作）。

## 6. 非目标
任何新的后端授权强制——本 spec 只根据真实 token claim 隐藏/展示 UI；绝不能编造一个后端自身并不强制的权限边界（`11-security-and-authorization` §2 中关于 `ApprovalController` 明确自我标记的缺口）。

## 7. 前置条件
存在一个有效会话（SPEC-SC-001）。

## 8. 输入
会话 token 的角色/scope claim。

## 9. 详细行为
从 token 解析角色/scope；据此有条件渲染仅管理员可见 vs. 坐席可见的 UI；针对已知的 `ApprovalController` 缺口，向任何已认证用户渲染 grant/deny UI（与真实后端行为一致），同时在代码注释/文档中如实标记，而不是悄悄假装存在更细粒度的控制。

## 10. 交互状态迁移
不适用——一个渲染条件关切。

## 11. 业务不变量
一条新不变量：控制台绝不能因角色而把一个后端实际会接受的操作展示为不可用（反之亦然），除非有文档记录的后端缺口使这在客户端无法完全解决。

## 12. 幂等策略
不适用。

## 13. 消费/依赖的契约
domain 01 签发的会话 token 的 claim 形状（真实、已实现）。

## 14. 安全
本 spec 明确只是 UI 便利性，从不是一个安全边界——重申 `11-security-and-authorization` §2 自己如实的表述。

## 15. 可观测性
除标准会话可观测性外不适用。

## 16. 错误场景
一个角色 claim 异常/缺失的 token——默认渲染最受限的 UI，绝不是最宽松的。

## 17. 验收场景
一个坐席角色会话看不到仅管理员可见的 reconciliation 触发器；管理员角色会话能看到。

## 18. 先写测试
针对不同角色 claim fixture 渲染控制台外壳的组件测试，断言正确的条件渲染。

## 19. 完成定义
针对坐席和管理员两种角色 fixture 都验证了 UI 门控；`ApprovalController` 缺口被内联文档记录，而不是悄悄用假强制绕过。
