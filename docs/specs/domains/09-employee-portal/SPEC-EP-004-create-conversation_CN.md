# SPEC-EP-004 — Create Conversation（创建会话）

> Domain: `09-employee-portal` | Phase: 02 — 对话核心 | 状态：Implemented

## 1. Spec 身份
`SPEC-EP-004`，实现 `UC-EP-01`。

## 2. 目标
调用（待建的）`POST /api/v1/conversations` 端点发起新会话，拿到一个真实的 `conversationId`。

## 3. 设计依据
`01-domain-model` §"Conversation"；`04-use-cases` UC-EP-01；`05-api-contracts` §2.1。

## 4. Actor
打开门户、没有活跃会话的已登录员工。

## 5. 范围
`useCreateConversation` hook 以及在第一条消息时触发它的空状态 UI。

## 6. 非目标
不实现后端端点本身——依赖 `03-agent-runtime-orchestration` 的 `SPEC-ARO-038`，在其落地前用 MSW mock（契约优先策略，roadmap §2.5）。

## 7. 前置条件
`AUTHENTICATED`；该员工没有既存的活跃/已转人工会话（恢复场景见 SPEC-EP-015）。

## 8. 输入
无（`{}`，遵循 `05-api-contracts` §2.1）。

## 9. 详细行为
第一次编写消息时，调用 `POST /api/v1/conversations` → 保存返回的 `conversationId` → 进入 SPEC-EP-005 的发消息流程。

## 10. 交互状态迁移
先于轮次状态机的 `IDLE` 态（`03-state-machine` §3.1）——必须先有会话，轮次才能开始。

## 11. 业务不变量
无直接相关；为 BI-EP-001（员工从一开始就只能看到自己的会话）建立前提条件。

## 12. 幂等策略
需要 `Idempotency-Key`，遵循 `08-transaction-and-outbox` §2——重试创建请求从不产生两个会话。

## 13. 消费/依赖的契约
`POST /api/v1/conversations`（待建，`SPEC-ARO-038`）——本 spec 自己的测试用 MSW mock。

## 14. 安全
需要（待建的）`conversations:create` scope（`11-security-and-authorization` §2）。

## 15. 可观测性
为这次调用生成 `traceparent` header（`12-observability-and-audit` §1）。

## 16. 错误场景
后端不可用——展示明确错误，前端从不在本地伪造一个假的 `conversationId`。

## 17. 验收场景
对着 MSW mock：调用返回一个 `conversationId`；真实端点建成后：同样如此，且真实工单出现在 ticket-workflow 数据库里（一旦 `SPEC-ARO-038` 存在，在兼容性测试里交叉验证）。

## 18. 先写测试
先写针对 MSW mock 的契约测试，再写 UI。

## 19. 完成定义
现在对着 mock 通过；真实后端端点建成后追加（而非重写）一个兼容性测试。
