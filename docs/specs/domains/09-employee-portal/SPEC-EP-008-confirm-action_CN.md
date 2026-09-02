# SPEC-EP-008 — Confirm Action（确认方案）

> Domain: `09-employee-portal` | Phase: 03 — 自助方案确认 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-008`，实现 `UC-EP-03` 的确认路径。

## 2. 目标
调用（待建的）确认端点，把返回的结果（`done`/`still-processing`/`awaiting-approval`，遵循 `SPEC-ARO-040` 自己的契约）渲染成一张持续更新的状态卡。

## 3. 设计依据
`04-use-cases` UC-EP-03；`05-api-contracts` §2.3；`SPEC-ARO-040`（本 spec 依赖的后端契约）。

## 4. Actor
正在看 `ProposedActionCard` 的员工。

## 5. 范围
`useConfirmAction` hook，以及 `ACTION_EXECUTING` 期间展示的执行状态卡。

## 6. 非目标
后端自己的限时等待/审批路由逻辑——本 spec 只如实渲染收到的三种结果之一，包括 `still-processing` 和 `awaiting-approval`（不只是视觉稿着重展示的"立即完成"这一种理想情况）。

## 7. 前置条件
轮次状态为 `AWAITING_CONFIRMATION`。

## 8. 输入
正在确认的 `actionId`。

## 9. 详细行为
点击确认 → 按钮立即禁用 → `ACTION_EXECUTING` → 渲染 `done`（成功卡）/ `still-processing`（"还在处理"提示，不是假的完成）/ `awaiting-approval`（如实的"等待人工"提示）→ `IDLE`。

## 10. 交互状态迁移
遵循 `03-state-machine` §3.1 的 `AWAITING_CONFIRMATION → ACTION_EXECUTING → IDLE`；三种子结果是本 spec 的渲染关注点，不是新的顶层状态。

## 11. 业务不变量
BI-EP-003（从不在没有明确确认的情况下执行——由按钮点击作为唯一触发来强制）和 BI-EP-005（真实结果是 `still-processing`/`awaiting-approval` 时，从不伪造一个 `done`）。

## 12. 幂等策略
每次确认点击一个 `Idempotency-Key`；按钮点击后立即禁用，防止第二次真实触发（`09-concurrency-and-idempotency` §3）。

## 13. 消费/依赖的契约
确认端点（待建，`SPEC-ARO-040`）——在其落地前，用 MSW mock 覆盖三种结果分支。

## 14. 安全
需要（待建的）`conversations:confirm-action` scope。

## 15. 可观测性
确认调用带 `traceparent`。

## 16. 错误场景
执行真正失败（不是网络错误）——下一条 agent 消息（新建议或转人工）由 SPEC-EP-005/012 正常渲染——本 spec 自己不重试。

## 17. 验收场景
`14-testing-strategy` §3.2 的 E2E-EP-02：确认一个低风险方案，看到执行完成状态；真实后端存在后，再补一个 `still-processing`/`awaiting-approval` 结果的场景。

## 18. 先写测试
在接入真实端点之前，先针对 MSW mock 写三种结果渲染的组件测试。

## 19. 完成定义
三种结果都能正确、如实渲染；`SPEC-ARO-040` 真实存在后追加兼容性测试。
