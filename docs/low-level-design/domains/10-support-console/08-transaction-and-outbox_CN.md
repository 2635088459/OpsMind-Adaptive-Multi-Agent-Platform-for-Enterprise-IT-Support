# Support Console — 事务与 Outbox

> **Document ID:** LLD-SC-008
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 前端不持有事务，完全依赖后端已有的原子性保证

与 09 号 domain 同一原则（见其 `08-transaction-and-outbox`）。真正的原子性——审批决策落库 + 触发工单状态流转 + outbox 事件——全部发生在 `06-policy-approval-governance`/`02-ticket-workflow` 内部，且已经在 2026-09-01 的集成验证里真实证明过这条链路能正确工作（`project-level-integration-verification` memory）。

## 2. Idempotency-Key 约定

所有产生副作用的操作（triage/assign/status-transitions/grant/deny）复用平台已有约定：前端生成 `Idempotency-Key`，重试时复用同一个值。与 09 号 domain 唯一的区别是：这里的"重试"更多来自坐席手误重复点击，而不是网络重试——但处理方式完全一致，不需要为此单独设计。

## 3. 本 domain 特有的一点：审批决策不可撤销，Idempotency-Key 的作用是防重复而非允许"改主意"

批准/拒绝一旦后端确认，坐席不能通过"再点一次"来撤销或改变决定——`Idempotency-Key` 只保证同一次点击的网络重试不会产生两次真实决策，不提供撤销机制。如果坐席真的点错了，走的是 `06-policy-approval-governance` 自己是否提供"撤销/revoke"能力（该 domain 确实有 revoke override 相关端点，具体是否适用于普通审批场景由那个 domain 自己的业务规则决定，本 LLD 不假设）。
