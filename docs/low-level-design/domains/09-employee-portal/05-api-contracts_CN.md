# Employee Portal — API 契约

> **Document ID:** LLD-EP-005
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## 1. 已经真实存在、可以直接对接的契约

这些不是本 LLD 设计的——是已经建成并在 2026-09-01 的项目级集成验证里真实跑通过的（见 `project-level-integration-verification` memory）。

### 1.1 登录（01-user-access-authentication）
```text
真实 Authorization Code + PKCE，Keycloak 真实 realm
GET  /oauth2/authorization/opsmind        → 302 到 Keycloak
                                           → 回调后 Set-Cookie: OPSMIND_SESSION
```
前端不用自己实现 OIDC 细节——这是 Spring Security 服务端会话（cookie-based），employee-portal 的 API 调用走这个已建立的会话，而不是自己持有/刷新 JWT（细节见 `11-security-and-authorization`）。

### 1.2 工单状态只读（02-ticket-workflow）
```text
GET /api/v1/tickets/{ticketId}
```
真实返回字段已在集成验证中确认：`ticketId, displayId, status, ...`（见该 domain 自己的 `05-api-contracts`）。employee-portal 直接消费，不重新定义 DTO 形状——`packages/api-contracts` 里的类型应该是从 `02-ticket-workflow` 的 OpenAPI 生成，不是手写。

### 1.3 工单创建（02-ticket-workflow，作为兜底路径）
```text
POST /api/v1/tickets   {title, description, applicationCode, source: "PORTAL"}
```
正常情况下工单由 agent-runtime 编排创建（见 §2.3）；这个端点在 `10-error-handling-and-reconciliation` 的降级路径里作为"agent 整个不可用时，员工仍然不能被卡住"的兜底手段——**必须**保留直连能力，不能假设对话式创建永远可用。

## 2. 需要新建、本 LLD 只声明契约形状（不越权设计后端内部实现）

以下端点全部标注为**待 `03-agent-runtime-orchestration` 立项新 SPEC**，形状是前端需求推导出的最小契约，最终以该 domain 自己的 API 契约文档为准。

### 2.1 创建会话
```http
POST /api/v1/conversations
```
```json
// 请求
{}
// 响应 201
{ "conversationId": "conv_...", "startedAt": "2026-09-01T09:41:00Z" }
```

### 2.2 发送一条消息
```http
POST /api/v1/conversations/{conversationId}/messages
Idempotency-Key: <uuid>   // 复用平台已有约定，见 §4
```
```json
// 请求
{
  "text": "嗨，我登录的时候 Duo 一直验证失败",
  "attachmentRefs": ["att_8f2c..."]
}
// 响应 200 —— 三选一
{ "type": "text", "text": "..." }
{ "type": "proposedAction", "actionId": "act_...", "summary": "...", "riskLevel": "MEDIUM", "requiresConfirmation": true }
{ "type": "escalation", "ticketId": "01a0...", "displayId": "INC-2483", "reason": "...", "assignedTeam": "现场支持" }
```

### 2.3 确认/拒绝一个方案
```http
POST /api/v1/conversations/{conversationId}/actions/{actionId}/confirm
POST /api/v1/conversations/{conversationId}/actions/{actionId}/decline
Idempotency-Key: <uuid>
```
`confirm` 触发 agent-runtime 内部编排（可能进一步调用 04/05/06 号 domain，对前端完全透明）。响应形状与 §2.2 一致（三选一，通常是执行结果的 `text` 或失败后的 `escalation`）。

### 2.4 工单状态实时推送
```http
GET /api/v1/tickets/{ticketId}/events
Accept: text/event-stream
Last-Event-ID: <resume-token>   // 断线重连时携带
```
```text
event: ticket.status.changed
data: {"ticketId":"...", "status":"IN_PROGRESS", "updatedAt":"..."}
```
按 shared technology-baseline §4 已经定死的"SSE 支持重连、心跳、Last-Event-ID"要求实现；这是 `02-ticket-workflow` 目前**没有**的新能力（现有的只有 REST 读取）。

## 3. 附件上传（归属待定的共享能力）

```http
POST /api/v1/attachments
Content-Type: multipart/form-data
```
```json
{ "attachmentId": "att_...", "objectRef": "s3://...", "thumbnailUrl": "..." }
```
**已与用户确认（2026-09-01）**：不归属任何现有 domain，作为独立的平台级共享能力立项——与 shared technology-baseline §7 已经决定的 MinIO/S3-compatible 对象存储配套，供所有 domain 未来复用（不只是 employee-portal 一家）。文档归属建议放在 `docs/low-level-design/shared/attachments/`（与 `shared/api`、`shared/events` 同级），具体契约/校验/病毒扫描钩子等留待该共享能力自己的设计文档展开，本 LLD 只声明 employee-portal 侧需要的最小契约形状。

## 4. Idempotency-Key 约定

沿用整个平台已经统一的约定（`ticket-workflow-service` 等已建成服务的真实模式）：所有会产生副作用的 POST 请求都带 `Idempotency-Key` header，值为客户端生成的 UUID，重复提交同一个 key 返回原结果而不是重复执行。

## 5. 本 domain 明确不做的事

- 不直接调用 `04-memory-knowledge`、`05-tool-integration-gateway`、`06-policy-approval-governance` 的任何 API —— 全部通过 `03-agent-runtime-orchestration` 编排，前端只认识"对话"这一层契约。
- 不自己签发或校验 JWT —— 复用 01 号 domain 已经建成的会话机制。
- 不定义工单的状态机语义 —— `TicketStatus` 的取值完全照抄 `02-ticket-workflow` 自己的定义，任何新增状态值必须先由那个 domain 变更，前端才能跟进。
