# Agent Runtime Orchestration — Phase 10：对话式接入（Conversational Intake）

> **Document ID:** IMP-ARO-P10
> **Domain:** `03-agent-runtime-orchestration`
> **状态:** Draft（新增 phase，domain 03 原有 36 个 SPEC/phase 00-09 均已实现，本 phase 是后加的）
> **触发原因:** `09-employee-portal` LLD 依赖一个目前任何 domain 都不拥有的"对话轮次"能力，本 phase 就是补上这个缺口

---

## 1. 为什么是 phase 10，而不是重写已有 phase

domain 03 的 phase 00-09 已经真实实现且被标记为"entire domain roadmap complete"。本 phase **不重写、不推翻**任何已有设计——`WorkflowInstance`/`AgentTask`/`Checkpoint`/`ToolRequest` 这套领域模型完全复用，本 phase 只是在其上新增一种新的 `workflow_type` 和一条新的执行路径。

## 2. 先说清楚：现有真实代码是什么样的（不是猜的，是读代码确认的）

```text
WorkflowInstance 必须绑定一个已存在的真实 ticketId，通过消费 ticket.created.v1
（或调用真实存在的 POST / start_workflow，同样要求真实 ticket_id）才能创建。

start_workflow 需要调用方提前提供完整的 task_graph（预定义任务节点计划）。

AgentTask 的真实执行模式是异步轮询 worker：
  POST /internal/agent-runtime/v1/agent-tasks/claim
  POST /internal/agent-runtime/v1/agent-tasks/{id}/complete

每一次工具调用（ToolRequest）都要求一个 preceding_checkpoint_id（真实存在的业务不变量：
"每个外部副作用之前必须先写 checkpoint"），且工具执行完成本身是异步的
（SPEC-ARO-019 dispatch、SPEC-ARO-020 consume tool.completed/failed）。
```

这几条真实约束直接决定了本 phase 的设计，不是凭空发明的。

## 3. 三个已经和用户确认过的架构决定

1. **每一次对话，哪怕最终能秒解决，也必须先在 `02-ticket-workflow` 真实建一张工单**——`WorkflowInstance` 的现有约束不允许在没有工单的情况下存在。这不是产品偏好，是现有代码结构逼出来的结论。
2. **新增一条同步/内联执行路径，专门给对话轮次用**，不复用现有异步 claim/complete 队列——已与用户确认。
3. **写在 `agent-runtime-service` 内部**，新增一个 package（如 `interfaces/conversation/`），复用同一套 `WorkflowInstance`/`AgentTask`/`Checkpoint` 领域模型和同一个数据库 schema，不新建服务——已与用户确认。

## 4. 一个更深层、必须如实说明的发现：工具执行本身现在也是异步的

即使对话轮次本身做成同步/内联，`ToolRequest` 的真正完成（SPEC-ARO-020 消费 `tool.completed`/`tool.failed`）**仍然是异步的**——工具网关（`05-tool-integration-gateway`）自己就是这么设计的，不是本 phase 能绕过的东西。

这意味着 `09-employee-portal` 视觉稿里"确认后几秒内看到 ✓ 已完成"这个体验，真实实现方式是：

```text
确认请求 → 本 phase 新的内联执行器 → 写 checkpoint → 分发 ToolRequest
  → 短超时同步等待（如 3-5 秒，具体数值留给 phase 实施时压测决定）
    → 工具在超时内完成 → 直接在同一个 HTTP 响应里返回"已完成"
    → 工具没在超时内完成 → 返回"正在处理，稍后会更新"，
       之后工具真正完成时，通过 09 号 domain 已经设计好的
       工单状态 SSE（05-api-contracts §2.4）间接感知
       （因为此时这次对话轮次已经等价于一次真实的 ticket 状态变化）
```

不假装工具执行永远是瞬时的——这是一个真实的架构约束，如实反映在响应契约里（见 §6）。

## 5. 新增领域概念（扩展现有模型，不新建平行体系）

```text
workflow_type = "conversational_intake"          （新增枚举值，其余沿用 WorkflowInstance 原有字段）
task_type = "process_user_message"                （新增枚举值，AgentTask 原有字段）
task_type = "execute_confirmed_action"            （新增枚举值）
```

`conversationId`（09 号 domain 已经声明的前端类型）直接等于本 phase 的 `workflowInstanceId`，不新造一套 ID 体系。

## 6. 新增 API（公开面向前端，非 `/internal/`）

```text
POST /api/v1/conversations
  → 内部：调用 02-ticket-workflow 真实的 POST /api/v1/tickets（source=API）
  → 内部：用刚拿到的真实 ticketId 创建 WorkflowInstance(workflow_type=conversational_intake)
          （直接走内部命令，不经过 ticket-created 事件摄取端点，因为 ticketId 是本次请求自己刚创建的，
          不需要等一个事件回环）

POST /api/v1/conversations/{conversationId}/messages
  → 新建一个 AgentTask(task_type=process_user_message)
  → 本 phase 新的内联执行器同步执行：写 checkpoint → 查 04-memory-knowledge 知识库
    → 判定：直接回复文本 / 提出方案 / 判定需要转人工
  → 响应形状与 09 号 domain 05-api-contracts §2.2 声明的三选一一致

POST /api/v1/conversations/{conversationId}/actions/{actionId}/confirm
POST /api/v1/conversations/{conversationId}/actions/{actionId}/decline
  → confirm 触发 §4 描述的"短超时同步等待"执行路径
  → 若方案的风险等级达到需要真实审批（HIGH/CRITICAL，具体阈值沿用
    06-policy-approval-governance 已有的 RiskLevel 语义），
    则调用该 domain 真实的 request-approval 路径，响应里明确返回
    "等待人工审批"，不假装能立即完成

GET /api/v1/conversations/{conversationId}
  → 复用已经真实存在的 GET /{workflow_instance_id} 查询能力，做一层对话形状的展示映射
```

## 7. 转人工（Escalation）：不创建第二张工单，是同一张工单的分诊

因为工单在 §3 决定 1 里已经从第一条消息起就存在，"转人工"不是"创建工单"，而是：本次 `conversational_intake` 工作流判定自己无法/不适合继续自动处理，调用 `02-ticket-workflow` 真实的 `POST /{ticketId}/triage` 端点（`actor_type` 传 `AUTOMATION_AGENT` 或等效值，该端点已经真实支持非人类 actor），把工单路由到正确的支持队列，然后本工作流实例正常结束——之后完全交给 `10-support-console` 的坐席处理，不再尝试自动化。

## 8. 一个必须补的新基础设施依赖：agent-runtime-service 需要真实的服务身份

`agent-runtime-service` 调用 `02-ticket-workflow`/`06-policy-approval-governance` 的真实端点，都需要携带一个真实、有效的 JWT（这些端点的鉴权是真实强制的，2026-09-01 集成验证已经确认）。目前 `agent-runtime-service` **没有**任何机制获取这样的 token。需要新增：一个真实的 Keycloak client_credentials 客户端（结构上与今天集成验证里为测试搭的 `integration-test-client`同类，但这次是给 `agent-runtime-service` 自己用的生产级服务身份），并授予 `tickets:create`、`ticket:triage` 等已经真实存在的 scope。这是本 phase 的前置依赖，不是可选项。

## 9. 新增 Feature Specs

```text
SPEC-ARO-037-conversational-intake-workflow-type
SPEC-ARO-038-start-conversation-creates-ticket
SPEC-ARO-039-inline-message-turn-execution
SPEC-ARO-040-confirm-decline-with-bounded-wait
SPEC-ARO-041-escalation-via-existing-triage
SPEC-ARO-042-resume-conversation-query
SPEC-ARO-043-service-identity-for-outbound-calls
```

## 10. 明确不做的事（本 phase non-goal）

- 不重新设计 `02-ticket-workflow`/`06-policy-approval-governance` 已有的真实端点，只作为调用方接入
- 不假装工具执行永远同步完成（§4 已如实说明）
- 不做多轮对话的长期记忆个性化（09 号 domain memory 里已经记录的"per-user RAG 隔离"缺口，本 phase 不解决，留给 `04-memory-knowledge` 未来自己的设计）
- 不修改 `WorkflowInstance`/`AgentTask` 现有字段的语义，只新增枚举值

## 11. 退出标准

```text
真实创建一个 conversation → 真实在 ticket-workflow 数据库里看到对应工单
发一条消息 → 同步收到 agent 的文本回复（走 04-memory-knowledge 真实检索）
提出一个低风险方案 → 确认 → 短超时内看到真实工具执行结果，或明确的"仍在处理"提示
提出一个高风险方案 → 确认 → 真实在 policy-approval-governance 看到一条审批请求
判定无法处理 → 真实调用 triage 端点，工单出现在 support-console 队列里
```

## 12. 立即下一步

```text
1. 评审本 phase 计划，尤其是 §4 的短超时同步等待方案（超时秒数需要真实压测）
2. 补齐 §8 的服务身份基础设施（Keycloak client）
3. 逐个撰写 SPEC-ARO-037~043
4. 在真实 docker-compose 栈上端到端验证（复用 2026-09-01 已经搭好的 Keycloak/RabbitMQ/Postgres 环境）
```
