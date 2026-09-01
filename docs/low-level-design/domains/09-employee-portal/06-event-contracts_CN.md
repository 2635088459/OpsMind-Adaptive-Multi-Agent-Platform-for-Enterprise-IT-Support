# Employee Portal — 事件契约

> **Document ID:** LLD-EP-006
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## 1. 本 domain 不发布任何 RabbitMQ 事件

employee-portal 是浏览器里运行的纯前端应用，不接入 RabbitMQ（这是后端服务间的事件总线，见 shared baseline §8），也不拥有 outbox。这一篇之所以还存在（而不是直接跳过），是为了明确定义前端**消费**的两类实时流，避免和"事件契约"这个词在其他 domain 里的含义混淆。

## 2. 消费的实时流（SSE，非 RabbitMQ 事件）

### 2.1 工单状态变化流
```text
GET /api/v1/tickets/{ticketId}/events
event: ticket.status.changed
data: {"ticketId","status","updatedAt"}
```
语义上对应 `02-ticket-workflow` 内部真实发生的状态迁移（`TicketStatusChanged` 领域事件），但**不是**直接订阅 RabbitMQ——是 `02-ticket-workflow` 自己需要新增一个把内部状态变化转发成 SSE 的网关层（这个转发本身是那个 domain 的实现细节，本文档不越权设计）。

### 2.2 对话轮次的流式响应（可选，phase 2+）
MVP 阶段 `05-api-contracts` §2.2 的 `POST .../messages` 是同步请求-响应；如果后续要做"agent 打字机效果"的流式体验，会追加：
```text
POST /api/v1/conversations/{id}/messages/stream
Accept: text/event-stream
event: token
data: {"text": "看"}
event: token
data: {"text": "了"}
...
event: done
data: {"type": "text" | "proposedAction" | "escalation", ...}
```
本期（MVP）不做——先用同步响应把功能跑通，流式打字效果作为 phase 2 的体验优化，明确写进 non-goal（见 roadmap）。

## 3. 与后端事件信封的关系

`02-ticket-workflow` 等后端 domain 之间使用的事件信封（`eventId/eventType/producer/schemaVersion/...`，见 shared baseline §8）**不会**原样透传给前端——SSE payload 是为前端消费裁剪过的精简形状（`ticket.status.changed` 的 data 只有 3 个字段），不是把内部事件信封直接转发。这是刻意的边界：前端不应该、也不需要知道 `correlationId`/`causationId` 这类后端内部溯源字段。
