# Support Console — API 契约

> **Document ID:** LLD-SC-005
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 已经真实存在、直接对接的契约

```text
GET  /api/v1/support-queues/{queueId}/tickets      → 02-ticket-workflow
GET  /api/v1/tickets/{ticketId}                    → 02-ticket-workflow
GET  /api/v1/tickets/{ticketId}/timeline           → 02-ticket-workflow
POST /api/v1/tickets/{ticketId}/triage             → 02-ticket-workflow
POST /api/v1/tickets/{ticketId}/assign             → 02-ticket-workflow
POST /api/v1/tickets/{ticketId}/status-transitions → 02-ticket-workflow
GET  /api/v1/tool-requests/{toolRequestId}          → 05-tool-integration-gateway
GET  /api/v1/governance-audit-records               → 06-policy-approval-governance
GET  /api/v1/approval-requests/{approvalRequestId}  → 06-policy-approval-governance
POST /api/v1/approval-requests/{id}:grant / :deny   → 06-policy-approval-governance（2026-09-01 已现场验证）
```

字段形状均以各自 domain 自己的 `05-api-contracts` 为准，本文档不重复定义，只声明 support-console 侧的调用方式。

## 2. 版本控制约定（If-Match）

所有工单变更类操作（triage/assign/status-transitions）复用 `02-ticket-workflow` 已经真实存在的乐观锁约定：请求携带 `If-Match: <当前已知版本号>`，后端版本不匹配时返回 409，前端据此进入 `VERSION_CONFLICT`（BI-SC-005）。support-console **不引入自己的一套版本控制机制**，完全遵循已有约定。

## 3. AiLogEntry 聚合：前端聚合而非新建 BFF 端点

`01-domain-model` §5 提出的两个方案里，本 LLD 选择**前端并发调用三个真实端点后本地拼接**，理由：

- 三个端点已经存在，性能上三次并发请求（非串行）足够快，不需要引入一个新的聚合层增加运维复杂度
- 一个新的 BFF 聚合端点本身需要新的服务/部署单元，与"support-console 应该是纯消费方、不引入新业务逻辑归属"的原则冲突（呼应 09 号 domain `01-domain-model` 里同样的边界原则）
- 如果未来发现前端聚合的字段拼接逻辑过于复杂/需要跨请求缓存优化，再考虑引入专门的聚合层——这是一个刻意延后的决定，不是没考虑过

```typescript
// 伪代码，说明聚合方式而非最终实现
const [timeline, toolRequests, auditRecords] = await Promise.all([
  fetchTicketTimeline(ticketId),
  fetchRelatedToolRequests(ticketId),
  fetchGovernanceAuditRecords(ticketId),
]);
const aiLog = mergeIntoTimeline(timeline, toolRequests, auditRecords); // 纯前端函数，按 occurredAt 排序
```

## 4. 队列实时更新（MVP：轮询）

```http
GET /api/v1/support-queues/{queueId}/tickets?since={lastPolledAt}
```
每 15-30 秒轮询一次（具体间隔留给 phase 实施时按真实负载测试调整）。SSE 推送作为 phase 2+ 明确 non-goal，不在本期实现，也不在本 LLD 假装它已经决定好契约形状——真正要做时需要 `02-ticket-workflow` 新增能力，走和 09 号 domain 一样的 contract-first 策略。

## 5. 可观测性页面的两类外链

```text
Trace 深链：https://{grafana-host}/explore?...traceID={AiLogEntry.traceId}
LangSmith 深链：由 07-evaluation-improvement 的 API 响应直接返回完整外链 URL，前端不自己拼接 LangSmith 的 URL 格式（避免 LangSmith 自己的 URL scheme 变化时前端要跟着改）
```
