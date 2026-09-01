# Employee Portal — 领域模型

> **Document ID:** LLD-EP-001
> **Domain:** `09-employee-portal`
> **状态:** Draft
> **技术基线:** `docs/low-level-design/shared/technology-baseline/README_CN.md`（React 19 + Vite）
> **产品方向依据:** `frontend-product-vision` memory（2026-09-01 与用户确认的视觉稿）

---

## 1. 这个 domain 到底"拥有"什么

与 01-08 号后端 domain 不同，employee-portal **不拥有任何持久化的业务状态**。它是一个纯前端应用，真正的业务事实（工单状态、审批决策、知识检索结果）永远存在于对应的后端 domain 自己的数据库里：

```text
Ticket 的真相      → 02-ticket-workflow（ticket-workflow-service）
审批的真相         → 06-policy-approval-governance
知识/记忆的真相    → 04-memory-knowledge
Agent 会话推进的真相 → 03-agent-runtime-orchestration（需要新增能力，见 §5）
```

employee-portal 自己只拥有：**会话的客户端视图模型**（Conversation/Message 这类瞬时 UI 状态）和**浏览器本地存储**（草稿、离线缓存）。这不是偷懒的简化——是刻意的架构决定：任何试图在前端"自己记一份工单状态"的做法，都会造成前端和后端事实不一致（BI-EP-004/005，见 `02-business-invariants`）。

## 2. 核心概念

### Conversation（会话）
一次员工与 OpsMind 的对话会话。客户端聚合，非后端持久化实体。

```text
Conversation
  conversationId: string          // 由 agent-runtime 侧的会话/工作流实例派生
  ticketId: string | null         // 一旦升级/创建工单，回填这个字段
  messages: Message[]
  status: "active" | "escalated" | "closed"
  startedAt: datetime
```

### Message（消息）
```text
Message
  messageId: string
  role: "user" | "agent" | "system"
  text: string
  attachments: Attachment[]
  proposedAction: ProposedAction | null   // 仅 agent 消息可能带
  escalation: EscalationNotice | null     // 仅 agent 消息可能带
  createdAt: datetime
```

### Attachment（证据文件）
```text
Attachment
  attachmentId: string
  filename: string
  mimeType: string
  sizeBytes: number
  objectRef: string        // 对象存储引用（MinIO/S3-compatible，见 shared baseline §7）
  thumbnailUrl: string | null
  uploadStatus: "uploading" | "ready" | "failed"
```

### ProposedAction（agent 提出的处理方案）
agent 判断自己有权限直接处理时，先说明要做什么、再等确认（对应产品视觉稿里的"确认，帮我处理"按钮）。

```text
ProposedAction
  actionId: string
  summary: string              // 人话解释，如"重新绑定 Duo 设备配对"
  riskLevel: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"   // 与 06 号 domain 的 RiskLevel 同名同值，保持跨端一致
  requiresConfirmation: boolean
  status: "proposed" | "confirmed" | "declined" | "executing" | "done" | "failed"
```

### EscalationNotice（转人工通知）
agent 判断自己无权限/无能力处理时附带的通知，携带真实创建的 ticketId。

```text
EscalationNotice
  ticketId: string
  reason: string          // "这是物理硬件损坏，需要现场检修"
  assignedTeam: string | null
```

### TicketStatusView（工单进展只读投影）
不是本 domain 的实体，是对 `02-ticket-workflow` 真实 Ticket 聚合的**只读投影**，通过 API/SSE 拉取，绝不本地推断。

```text
TicketStatusView
  ticketId: string
  displayId: string        // 如 "INC-2483"
  status: TicketStatus     // 与 ticket-workflow 的真实状态机同名同值
  category: string
  assignedTeam: string | null
  slaDeadline: datetime | null
  updatedAt: datetime
```

### UserSession（登录会话）
由 `01-user-access-authentication` 的真实 Keycloak OIDC 流程产出，本 domain 只消费，不签发。

```text
UserSession
  subject: string           // JWT sub
  displayName: string
  scopes: string[]
  accessTokenExpiresAt: datetime
```

## 3. 一个关键、必须诚实说明的跨 domain 依赖缺口

产品视觉稿里"用户发消息 → agent 结合上下文+知识库分析 → 给出方案或转工单"这整套**对话式交互**，目前在后端**没有任何一个 domain 拥有这个能力**：

- `03-agent-runtime-orchestration` 现有的 `WorkflowInstance` 模型是为"编排一个已经存在的 ticket 的自动化处理流程"设计的，不是为"一次同步的、有来有回的对话轮次"设计的。
- 不存在任何 `POST /conversations/{id}/messages` 这样的同步会话端点。

**这不是 employee-portal 自己能补的坑**——对话轮次的推进（调用 LLM、检索知识库、决定是否有权限执行、决定是否要审批、决定是否创建工单）本质上是业务编排逻辑，按照本项目一贯的 domain 边界原则，只能归属 `03-agent-runtime-orchestration`，不能让前端自己拼装。

**结论**：`03-agent-runtime-orchestration` 需要新增一批 SPEC-ARO-0xx（会话轮次相关），employee-portal 的 `05-api-contracts` 假设这批契约存在，但本 LLD 不越权替 03 号 domain 设计其内部实现——只声明前端需要的契约形状（见 `05-api-contracts` §2）。这是本 LLD 集里最重要的一条"已知依赖，非本域缺口"标注。

## 4. 与既有 domain 的关系图

```text
employee-portal (09, 纯前端)
    │ OIDC 登录
    ▼
user-access-authentication (01) —— 已建成，已现场验证
    │
    │ 对话轮次（新增契约，见 §3）
    ▼
agent-runtime-orchestration (03) —— 编排逻辑已存在，会话端点待建
    │                    │
    │ 知识检索             │ 无权限时创建工单
    ▼                    ▼
memory-knowledge (04)   ticket-workflow (02) —— 已建成，已现场验证
    │ 有权限时执行           │
    ▼                    │
tool-integration-gateway (05)   │ 高风险时审批
                          ▼
                   policy-approval-governance (06) —— 已建成，已现场验证
```

employee-portal 只直接对接 01、02（工单状态只读）、03（一旦建成）三个 domain 的 API；04/05/06 都是通过 03 间接编排，前端从不直连。
