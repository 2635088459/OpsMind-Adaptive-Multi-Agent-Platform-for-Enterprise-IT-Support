# SPEC-EP-009 — Decline Action（拒绝方案）

> Domain: `09-employee-portal` | Phase: 03 — 自助方案确认 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-009`，实现 `UC-EP-03` 的拒绝路径。

## 2. 目标
调用（待建的）拒绝端点，让轮次回到 `IDLE`，零后端副作用。

## 3. 设计依据
`04-use-cases` UC-EP-03（替代流程）；`05-api-contracts` §2.3。

## 4. Actor
正在看 `ProposedActionCard`、选择不继续的员工。

## 5. 范围
`useDeclineAction` hook 以及"先不用"按钮的行为。

## 6. 非目标
任何重试或重新提议逻辑——拒绝就是结束这个方案；员工可以正常通过 SPEC-EP-005 问新问题。

## 7. 前置条件
轮次状态为 `AWAITING_CONFIRMATION`。

## 8. 输入
正在拒绝的 `actionId`。

## 9. 详细行为
点击"先不用" → 调用拒绝 → `IDLE`，全程不尝试任何执行。

## 10. 交互状态迁移
遵循 `03-state-machine` §3.1 的替代边，直接 `AWAITING_CONFIRMATION → IDLE`。

## 11. 业务不变量
BI-EP-003（更进一步地满足——完全没有副作用）。

## 12. 幂等策略
每次拒绝点击一个 `Idempotency-Key`，与确认路径的约定一致（`09-concurrency-and-idempotency` §3）。

## 13. 消费/依赖的契约
拒绝端点（待建，`SPEC-ARO-040`）——真实存在前用 MSW mock。

## 14. 安全
与 SPEC-EP-008 相同的 scope（后端自己的契约里 `conversations:confirm-action` 同时覆盖确认和拒绝）。

## 15. 可观测性
拒绝调用带 `traceparent`。

## 16. 错误场景
拒绝时网络失败——像其他有副作用的调用一样重试；拒绝失败从不悄悄当作已拒绝处理（卡片保持在 `AWAITING_CONFIRMATION`，直到拒绝真正成功）。

## 17. 验收场景
点击"先不用"产生零条 tool-request/approval-request 记录（真实后端存在后，遵循 `SPEC-ARO-040` 自己的验收标准验证）。

## 18. 先写测试
断言拒绝时从不发起任何执行相关网络调用的组件测试。

## 19. 完成定义
拒绝被证明零副作用——既通过 MSW mock 自己的调用断言，也（真实后端可用后）通过真实后端的数据库状态验证。
