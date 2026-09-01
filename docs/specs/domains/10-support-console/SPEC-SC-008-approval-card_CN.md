# SPEC-SC-008 — Approval Card（审批卡片）

> Domain: `10-support-console` | Phase: 04 — 审批处理 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-SC-008`，实现 `UC-SC-03` 的渲染一半。

## 2. 目标
把一个待处理的 `ApprovalRequest`（真实，属于 `policy-approval-governance`，domain 06）渲染为一张详情卡，展示 agent 想做什么以及为什么需要人工签字。

## 3. 设计依据
`01-domain-model` §"ApprovalCard"；`04-use-cases` UC-SC-03；domain 06 真实的 `ApprovalRequest`/`ApprovalDecision` 模型。

## 4. Actor
正在查看一个带待处理审批请求工单的支持坐席/管理员。

## 5. 范围
拉取并渲染审批请求详情：提议的操作、其关联的策略决策、请求方上下文。

## 6. 非目标
grant/deny 操作本身（SPEC-SC-009）。

## 7. 前置条件
一个工单关联一个待处理的 `ApprovalRequest`。

## 8. 输入
`approvalRequestId`（或按工单范围的查找）。

## 9. 详细行为
拉取真实的审批请求详情（遵循 domain 06 的 `GET /approval-requests/{id}`，由 `SPEC-PG-010` 新增）并渲染为一张卡：什么操作、针对什么目标、由哪个 agent/workflow 请求，带 `policyDecisionId` 上下文。

## 10. 交互状态迁移
不适用——一个只读详情渲染；grant/deny 迁移是 SPEC-SC-009 的关切。

## 11. 业务不变量
BI-SC（保真度）——卡片必须展示真实的待处理请求，绝不能是陈旧或本地缓存却当作当前的，鉴于本 domain 真实存在的多 agent 协作风险（BI-SC-005）。

## 12. 幂等策略
不适用——是一个 `GET`。

## 13. 消费/依赖的契约
`GET /approval-requests/{id}`——真实、已实现（遵循 `SPEC-PG-010`，在记忆中确认已关闭）。

## 14. 安全
受制于已知的 `ApprovalController` 细粒度授权缺口（SPEC-SC-002 §6）——目前任何已认证的控制台用户都能查看，与真实后端行为一致。

## 15. 可观测性
拉取时带 `traceparent`。

## 16. 错误场景
在查看这张卡的时候，该审批请求已被别人决策（在多 agent 协作 domain 中真实存在的竞态）——如实渲染实际当前状态（SPEC-SC-017 进一步强化这个特定竞态）。

## 17. 验收场景
一个待处理的审批请求，从真实后端形状正确渲染其操作、目标和请求方。

## 18. 先写测试
针对真实 `GET /approval-requests/{id}` 响应形状的组件测试。

## 19. 完成定义
卡片针对真实契约 fixture 正确渲染；接入后确认一次实时集成检查。
