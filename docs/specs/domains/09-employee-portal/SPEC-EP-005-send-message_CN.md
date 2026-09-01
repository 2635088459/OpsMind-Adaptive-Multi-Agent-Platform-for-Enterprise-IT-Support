# SPEC-EP-005 — Send Message（发送消息）

> Domain: `09-employee-portal` | Phase: 02 — 对话核心 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-EP-005`，实现 `UC-EP-02`。

## 2. 目标
发送一条消息，渲染返回的三选一响应（文本/方案提议/转人工）中的任意一种。

## 3. 设计依据
`01-domain-model` §"Message"；`04-use-cases` UC-EP-02；`05-api-contracts` §2.2。

## 4. Actor
已有 `conversationId` 的已登录员工。

## 5. 范围
`MessageComposer` 组件、`useSendMessage` hook，以及三种响应形状的渲染路由。

## 6. 非目标
渲染 `ProposedActionCard` 的确认/拒绝按钮（SPEC-EP-007）或 `EscalationNotice`（SPEC-EP-012）——本 spec 只把响应路由给正确的渲染器，具体渲染归那些 spec 所有。

## 7. 前置条件
轮次状态机处于 `IDLE`（`03-state-machine` §3.1）；已存在 `conversationId`（SPEC-EP-004）。

## 8. 输入
`{text, attachmentRefs[]}`。

## 9. 详细行为
点击发送 → `SENDING` → `AWAITING_AGENT` → 三选一响应之一 → 按 `03-state-machine` §3.1 迁移到 `IDLE`/`AWAITING_CONFIRMATION`/`ESCALATED`。

## 10. 交互状态迁移
`03-state-machine` §3.1 的完整轮次状态机，除去归 SPEC-EP-007/008/009 所有的确认/拒绝子状态。

## 11. 业务不变量
本 spec 单独不违反 BI-EP-001~007 任何一条；BI-EP-002（附件就绪）由输入框在任何附件未 `ready` 时禁用发送来强制。

## 12. 幂等策略
每次发送尝试一个 `Idempotency-Key`，重试时复用（`09-concurrency-and-idempotency` §1）；发送按钮在 `SENDING` 期间禁用作为第二道防线。

## 13. 消费/依赖的契约
`POST /api/v1/conversations/{id}/messages`（待建，`SPEC-ARO-039`）——目前用 MSW mock。

## 14. 安全
需要（待建的）`conversations:message` scope。

## 15. 可观测性
每次发送都带 `traceparent`。

## 16. 错误场景
超时/5xx——带退避重试，之后进入 `AGENT_UNAVAILABLE`（`10-error-handling-and-reconciliation` §2.1），完整处理在 SPEC-EP-018。

## 17. 验收场景
`14-testing-strategy` §3.2 的 E2E-EP-01：发一条消息，收到纯文本回复。

## 18. 先写测试
响应形状路由的单元测试；针对 MSW mock、覆盖三种形状的契约测试。

## 19. 完成定义
三种响应形状都能正确渲染；重复点击发送按钮从不产生重复消息（由测试验证，不只是 UI 禁用）。
