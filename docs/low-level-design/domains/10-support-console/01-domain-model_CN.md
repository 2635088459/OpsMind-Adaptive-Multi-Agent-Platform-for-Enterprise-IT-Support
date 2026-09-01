# Support Console — 领域模型

> **Document ID:** LLD-SC-001
> **Domain:** `10-support-console`
> **状态:** Draft
> **技术基线:** `docs/low-level-design/shared/technology-baseline/README_CN.md`（React 19 + Vite，与 09 号 domain 共享同一套技术栈）

---

## 1. 与 employee-portal 的关键差异

09 号 domain 有一个巨大的跨域缺口（对话轮次能力后端完全不存在）。10 号 domain **情况明显更好**：坐席/管理员需要的大部分能力，后端已经真实建成——这是一个纯粹的"把已有真相聚合展示出来"的前端，而不是需要后端先补一大块新业务能力。已确认真实存在的支撑端点：

```text
GET /api/v1/tickets/{ticketId}/timeline          → 02-ticket-workflow（真实）
GET /api/v1/governance-audit-records             → 06-policy-approval-governance（真实）
GET /api/v1/tool-requests/{toolRequestId}         → 05-tool-integration-gateway（真实）
POST /api/v1/approval-requests/{id}:grant / :deny → 06-policy-approval-governance（真实，2026-09-01 已现场验证）
GET /api/v1/support-queues 相关查询                → 02-ticket-workflow（真实，QuerySupportQueueApplicationService）
```

仍然缺失的（本篇 §5 详细列出）：队列级实时推送、评测/灰度数据的坐席可读聚合视图。缺口比 09 号 domain 小得多。

## 2. 核心概念

### QueueView（分诊队列视图）
对 `02-ticket-workflow` 真实 support queue 查询能力的展示层封装，非独立持久化实体。

```text
QueueView
  queueId: string
  teamName: string
  items: QueueItem[]
```

```text
QueueItem
  ticketId, displayId, title
  severity: "CRITICAL" | "HIGH" | "MEDIUM"   // 前端展示分级，映射自 Ticket.priority
  assignee: { type: "agent" | "human"; name: string } | null
  slaDeadline: datetime | null
  status: TicketStatus       // 与 02 号 domain 同名同值
```

### TicketDetailView（工单详情 + AI 处理记录）
```text
TicketDetailView
  ticket: TicketStatusView          // 复用 09 号 domain 已定义的只读投影形状
  requester: { name, department }
  aiLog: AiLogEntry[]               // 见下
  pendingApproval: ApprovalRequestView | null
```

### AiLogEntry（agent 处理记录条目）
聚合自 `02-ticket-workflow` 的 timeline + `05-tool-integration-gateway` 的 tool-request 详情 + `06-policy-approval-governance` 的 audit 记录——**是一个跨三个后端 domain 的聚合视图**，本身不是任何一个 domain 的原生数据结构。

```text
AiLogEntry
  step: string              // "匹配知识库标准处理流程" / "触发高风险审批" 等人话摘要
  sourceDomain: "ticket-workflow" | "tool-integration-gateway" | "policy-approval-governance"
  sourceRef: string          // 对应真实记录的 id，便于点击跳转细节
  status: "done" | "pending" | "failed"
  occurredAt: datetime
  traceId: string | null     // 用于 Observability 页面的 Tempo 深链，见 04-use-cases
```

### ApprovalRequestView（审批卡片）
直接对应 `06-policy-approval-governance` 真实的 `ApprovalRequest`，字段照抄，不重新定义语义。

```text
ApprovalRequestView
  approvalRequestId, ticketId
  requestedAction: string
  riskLevel: RiskLevel        // 与后端同名同值：LOW/MEDIUM/HIGH/CRITICAL
  scopeNote: string           // 如"24 小时后自动失效"
  status: "REQUESTED" | "GRANTED" | "DENIED" | ...  // 与后端真实状态机同值
```

### OperatorSession（坐席登录会话）
与 09 号 domain 的 `UserSession` 同构，但携带的 scope 不同（`ticket:triage`/`ticket:assign`/审批相关等，均已是后端真实存在的 scope）。

## 3. 与 08 号 domain（observability-platform）的关系

`08-observability-platform` 是纯基础设施（无自己的业务服务，见其 memory 记录），support-console 的"可观测性·评测"页面不调用一个不存在的"08 号服务 API"，而是：

- 对 Trace 瀑布图：用 `AiLogEntry.traceId` 拼出 Tempo/Grafana 的深链 URL，直接外链跳转，不在前端自己渲染完整 trace（渲染由 Grafana/Tempo 自己的界面负责）
- 对评测/灰度对比表：调用 `07-evaluation-improvement` 真实的 EvaluationRun 相关查询能力（该 domain 已建成，具体字段以那个 domain 自己的 API 契约为准，本 LLD 不重复定义）

## 4. 与 employee-portal 共享、不重复定义的类型

`TicketStatusView`、`RiskLevel`、`TicketStatus` 这几个类型两个 domain 都要用——放进 `packages/api-contracts`（两个 app 共享），不允许 09/10 号各自维护一份可能漂移的定义。

## 5. 仍然缺失、需要新增的后端能力

| 能力 | 现状 | 归属 |
|---|---|---|
| 队列级实时推送（新工单进队/优先级变化时坐席界面自动刷新） | 只有 REST 查询，无推送 | 02-ticket-workflow（新增） |
| `AiLogEntry` 的聚合本身 | 三个 domain 的数据分别真实存在，但没有一个端点把它们拼成一条时间线 | 待定：可以是 support-console 自己的前端聚合调用三次 API，也可以是新增一个 BFF 层。本 LLD 采用前者（前端聚合），理由见 `05-api-contracts` §3 |
