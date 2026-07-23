# OpsMind Ticket Workflow — 06 Event Contracts

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Asynchronous Event Contract  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `04-use-cases_CN.md`、`05-api-contracts_CN.md`  
> **消息中间件：** RabbitMQ  
> **Schema 标准：** JSON Schema Draft 2020-12  
> **交付语义：** At-least-once Delivery + Idempotent Consumer  
> **建议路径：** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/06-event-contracts_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow 与其他领域之间的异步 Event Contract。

它负责冻结：

- Event Envelope
- Event Type 与 Routing Key
- Ticket Workflow 发布的 Event
- Ticket Workflow 消费的 Event
- Producer 与 Consumer
- Payload 字段
- Schema Version
- Aggregate Version
- Trace 与 Correlation
- Ordering
- Idempotency
- Retry
- Dead Letter Queue
- Transactional Outbox
- PII 与 Secret 规则
- Compatibility
- Contract Test
- Manual Replay

本文档不定义 RabbitMQ 的完整基础设施部署文件，也不定义数据库表结构；这些内容将在后续文档中完成。

---

# 2. 核心事件原则

## 2.1 Event 表示已经发生的事实

正确：

```text
ticket.created
approval.granted
tool.execution.completed
verification.completed
```

不推荐将 Event 命名为命令：

```text
create.ticket
approve.action
execute.tool
```

Command 表示请求执行；Event 表示事实已经发生。

## 2.2 Event 不能绕过领域边界

Ticket Workflow 只能消费 Event 并调用明确的 Use Case 与 Domain Behavior。

禁止：

```text
Consumer receives arbitrary targetStatus
→ directly updates ticket.status
```

所有变化必须映射到：

```text
UC-xx
SM-xxx
BI-xxx
```

## 2.3 不假设 Exactly-once

RabbitMQ 与服务崩溃恢复不能提供端到端 Exactly-once。

OpsMind 使用：

```text
At-least-once Delivery
+
Processed Event Store
+
Business Idempotency
+
Optimistic Locking
```

## 2.4 Event Payload 最小化

Integration Event 只包含 Consumer 执行业务所需的数据。

禁止将以下对象整体序列化：

```text
Ticket Aggregate
JPA Entity
Agent Workflow Object
Approval Entity
Tool Credential
LLM Prompt
```

## 2.5 Domain Event 与 Integration Event 分离

```text
Domain Event
TicketResolved

Integration Event
ticket.resolved
```

Domain Event 不包含 RabbitMQ、Routing Key 或 JSON Schema 细节。

Application Layer 将 Domain Event 映射为 Integration Event，并写入 Outbox。

---

# 3. Event Type、Routing Key 与 Version

## 3.1 Logical Event Type

Envelope 中：

```json
{
  "eventType": "ticket.created",
  "eventVersion": "1.0"
}
```

## 3.2 RabbitMQ Routing Key

Routing Key 包含 Major Version：

```text
ticket.created.v1
ticket.resolved.v1
approval.granted.v1
tool.execution.completed.v1
```

规则：

```text
<domain>.<fact>.v<major>
```

复杂事件可以使用：

```text
tool.execution.result_unknown.v1
ticket.user_reply_requested.v1
```

## 3.3 Version 规则

`eventVersion` 使用：

```text
MAJOR.MINOR
```

例如：

```text
1.0
1.1
2.0
```

- Additive、Optional 字段：增加 Minor。
- 删除字段、改变语义、Optional 改 Required：增加 Major。
- Major Version 同时改变 Routing Key。
- Consumer 必须忽略未知 Optional 字段。

---

# 4. RabbitMQ Topology

## 4.1 Main Topic Exchange

```text
opsmind.events
```

类型：

```text
topic
durable = true
autoDelete = false
```

## 4.2 Dead Letter Exchange

```text
opsmind.dlx
```

类型：

```text
topic
durable = true
```

## 4.3 Ticket Workflow Inbound Queues

```text
ticket-workflow.agent-events.v1
ticket-workflow.approval-events.v1
ticket-workflow.tool-events.v1
ticket-workflow.verification-events.v1
```

建议绑定：

```text
ticket-workflow.agent-events.v1
  ← agent.#.v1
  ← ticket.classification.completed.v1

ticket-workflow.approval-events.v1
  ← approval.#.v1
  ← policy.action_auto_approved.v1

ticket-workflow.tool-events.v1
  ← tool.execution.#.v1

ticket-workflow.verification-events.v1
  ← verification.#.v1
```

## 4.4 DLQ

```text
ticket-workflow.agent-events.dlq.v1
ticket-workflow.approval-events.dlq.v1
ticket-workflow.tool-events.dlq.v1
ticket-workflow.verification-events.dlq.v1
```

## 4.5 Ordering 策略

MVP 对修改 Ticket 状态的 Inbound Queue 使用：

```text
x-single-active-consumer = true
```

目的：

- 降低同一 Queue 内并发乱序。
- 简化 Ticket 状态更新。
- 仍然不依赖 RabbitMQ 全局顺序。

未来横向扩展可以评估：

```text
consistent-hash exchange
+
ticketId partition key
```

无论使用何种 Queue 策略，Consumer 都必须检查 WorkflowId、AttemptId、ActionId 与 Version。

---

# 5. Canonical Event Envelope

```json
{
  "eventId": "01J0EVT8H0Z5E6K1W4Q8N7P2M3",
  "eventType": "ticket.created",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-23T16:30:00Z",
  "producer": "ticket-workflow-service",
  "environment": "local",
  "traceId": "8f03d65a4eb64c5b8abf920c56954c31",
  "correlationId": "INC-2048",
  "causationId": "cmd-create-ticket-1001",
  "ticketId": "01J0TICKET...",
  "workflowId": null,
  "aggregateType": "Ticket",
  "aggregateId": "01J0TICKET...",
  "aggregateVersion": 0,
  "sequence": 0,
  "partitionKey": "01J0TICKET...",
  "dataClassification": "INTERNAL",
  "payload": {}
}
```

---

# 6. Envelope 字段定义

| 字段 | 类型 | Required | 说明 |
|---|---|---:|---|
| eventId | string | yes | 全局唯一 Event ID |
| eventType | string | yes | Logical Event Type |
| eventVersion | string | yes | `MAJOR.MINOR` |
| occurredAt | date-time | yes | 事实发生时间，UTC |
| producer | string | yes | Producer Service Name |
| environment | string | yes | local / ci / demo / staging / prod |
| traceId | string | yes | OpenTelemetry Trace ID |
| correlationId | string | yes | Ticket Display ID 或业务 Correlation ID |
| causationId | string | no | 触发该 Event 的 Command/Event ID |
| ticketId | string | yes for ticket flow | Ticket Internal ID |
| workflowId | string/null | no | 当前 Agent Workflow |
| aggregateType | string | yes | Aggregate 类型 |
| aggregateId | string | yes | Aggregate ID |
| aggregateVersion | integer/null | no | Producer Aggregate Version |
| sequence | integer/null | no | Aggregate Event Sequence |
| partitionKey | string | yes | MVP 使用 ticketId |
| dataClassification | enum | yes | PUBLIC / INTERNAL / SENSITIVE |
| payload | object | yes | Event-specific Payload |

## 6.1 禁止字段

Envelope 和 Payload 禁止包含：

```text
password
accessToken
refreshToken
apiKey
sessionCookie
privateKey
authorizationHeader
rawCredential
```

---

# 7. Base Envelope JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://opsmind.dev/schemas/events/event-envelope-v1.json",
  "title": "OpsMind Event Envelope v1",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "eventId",
    "eventType",
    "eventVersion",
    "occurredAt",
    "producer",
    "environment",
    "traceId",
    "correlationId",
    "ticketId",
    "aggregateType",
    "aggregateId",
    "partitionKey",
    "dataClassification",
    "payload"
  ],
  "properties": {
    "eventId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "eventType": {
      "type": "string",
      "pattern": "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
    },
    "eventVersion": {
      "type": "string",
      "pattern": "^[1-9][0-9]*\\.[0-9]+$"
    },
    "occurredAt": {
      "type": "string",
      "format": "date-time"
    },
    "producer": {
      "type": "string",
      "minLength": 1,
      "maxLength": 100
    },
    "environment": {
      "type": "string",
      "enum": ["local", "ci", "demo", "staging", "prod"]
    },
    "traceId": {
      "type": "string",
      "minLength": 16,
      "maxLength": 64
    },
    "correlationId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    },
    "causationId": {
      "type": ["string", "null"],
      "maxLength": 128
    },
    "ticketId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "workflowId": {
      "type": ["string", "null"],
      "maxLength": 64
    },
    "aggregateType": {
      "type": "string",
      "minLength": 1,
      "maxLength": 100
    },
    "aggregateId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "aggregateVersion": {
      "type": ["integer", "null"],
      "minimum": 0
    },
    "sequence": {
      "type": ["integer", "null"],
      "minimum": 0
    },
    "partitionKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    },
    "dataClassification": {
      "type": "string",
      "enum": ["PUBLIC", "INTERNAL", "SENSITIVE"]
    },
    "payload": {
      "type": "object"
    }
  }
}
```

---

# 8. RabbitMQ Message Properties

Publisher 必须设置：

```text
message_id = eventId
type = eventType
content_type = application/json
content_encoding = utf-8
timestamp = occurredAt
correlation_id = correlationId
delivery_mode = 2
```

Headers：

```text
event_version
traceparent
producer
environment
ticket_id
workflow_id
data_classification
```

Event Envelope 仍然是 Source of Truth；Header 用于 Broker Routing 和快速诊断。

---

# 9. Ticket Workflow 发布事件总表

| ID | Event Type | Routing Key | 主要 Consumer |
|---|---|---|---|
| PUB-001 | ticket.created | ticket.created.v1 | Agent Runtime、SLA、Notification |
| PUB-002 | ticket.triaging_started | ticket.triaging_started.v1 | Agent Runtime、Timeline |
| PUB-003 | ticket.classified | ticket.classified.v1 | Agent Runtime、Analytics |
| PUB-004 | ticket.investigation_ready | ticket.investigation_ready.v1 | Agent Runtime |
| PUB-005 | ticket.user_reply_requested | ticket.user_reply_requested.v1 | Notification、Frontend/SSE |
| PUB-006 | ticket.user_replied | ticket.user_replied.v1 | Agent Runtime |
| PUB-007 | ticket.triage_resume_requested | ticket.triage_resume_requested.v1 | Agent Runtime |
| PUB-008 | ticket.investigation_resume_requested | ticket.investigation_resume_requested.v1 | Agent Runtime |
| PUB-009 | ticket.approval_wait_started | ticket.approval_wait_started.v1 | Frontend、Notification |
| PUB-010 | ticket.execution_ready | ticket.execution_ready.v1 | Tool Gateway |
| PUB-011 | ticket.verification_started | ticket.verification_started.v1 | Verification Agent |
| PUB-012 | ticket.resolved | ticket.resolved.v1 | Memory、Evaluation、Notification |
| PUB-013 | ticket.closed | ticket.closed.v1 | Memory、Evaluation、Analytics |
| PUB-014 | ticket.cancelled | ticket.cancelled.v1 | Agent Runtime、Approval、Tool Gateway |
| PUB-015 | ticket.reopened | ticket.reopened.v1 | Agent Runtime、SLA、Evaluation |
| PUB-016 | ticket.escalated | ticket.escalated.v1 | Support UI、Notification、Evaluation |
| PUB-017 | ticket.assigned | ticket.assigned.v1 | Support UI、Notification |
| PUB-018 | ticket.message_added | ticket.message_added.v1 | Timeline、Notification |
| PUB-019 | ticket.approval_rejected | ticket.approval_rejected.v1 | Agent Runtime、Frontend |
| PUB-020 | ticket.approval_expired | ticket.approval_expired.v1 | Agent Runtime、Frontend |
| PUB-021 | ticket.automation_failed | ticket.automation_failed.v1 | Support UI、Observability |
| PUB-022 | ticket.status_changed | ticket.status_changed.v1 | Timeline、Analytics、SLA |

---

# 10. Published Event Contracts

## PUB-001 ticket.created

### Mapping

```text
UC-01
SM-001
```

### Payload

```json
{
  "displayId": "INC-2048",
  "requesterIdHash": "sha256:...",
  "applicationCode": "HOUSING_PORTAL",
  "source": "PORTAL",
  "initialStatus": "NEW",
  "createdAt": "2026-07-23T16:30:00Z"
}
```

### Rules

- 不包含完整 Description。
- 不包含 Requester Email。
- `requesterIdHash` 仅用于允许的 Correlation。
- Agent Runtime 如需详情，通过受控 Internal Context API 查询。

---

## PUB-002 ticket.triaging_started

### Mapping

```text
UC-06
SM-002
```

### Payload

```json
{
  "workflowId": "wf-7788",
  "fromStatus": "NEW",
  "toStatus": "TRIAGING",
  "startedAt": "2026-07-23T16:31:00Z"
}
```

---

## PUB-003 ticket.classified

### Mapping

```text
UC-07
SM-003
```

### Payload

```json
{
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "classificationSource": "TRIAGE_AGENT",
  "confidence": 0.94,
  "classifiedAt": "2026-07-23T16:33:00Z"
}
```

`reasoningSummary` 不进入广播事件；需要时通过权限受控接口读取。

---

## PUB-004 ticket.investigation_ready

### Payload

```json
{
  "workflowId": "wf-7788",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH"
}
```

Consumer 使用 Event 启动或继续专业 Agent 调查。

---

## PUB-005 ticket.user_reply_requested

### Mapping

```text
UC-08
SM-004 / SM-007 / SM-031
```

### Payload

```json
{
  "requestId": "req-88",
  "workflowId": "wf-7788",
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "messageId": "msg-20",
  "resumeStatus": "INVESTIGATING",
  "requestedAt": "2026-07-23T16:40:00Z"
}
```

不在 Event 中重复完整 Message Body。

---

## PUB-006 ticket.user_replied

### Mapping

```text
UC-09
SM-005 / SM-006
```

### Payload

```json
{
  "requestId": "req-88",
  "messageId": "msg-21",
  "resumeStatus": "INVESTIGATING",
  "repliedAt": "2026-07-23T16:45:00Z"
}
```

Agent Runtime 通过 Internal Context API 读取实际 Message Body。

---

## PUB-007 ticket.triage_resume_requested

```json
{
  "workflowId": "wf-7788",
  "requestId": "req-88",
  "messageId": "msg-21",
  "resumeReason": "USER_REPLIED"
}
```

---

## PUB-008 ticket.investigation_resume_requested

```json
{
  "workflowId": "wf-7788",
  "resumeReason": "USER_REPLIED",
  "sourceReferenceId": "msg-21"
}
```

`resumeReason` 候选：

```text
USER_REPLIED
APPROVAL_REJECTED
APPROVAL_EXPIRED
TOOL_FAILED_SAFE
VERIFICATION_FAILED
AUTOMATION_RETRY
```

---

## PUB-009 ticket.approval_wait_started

### Mapping

```text
UC-11
SM-008
```

### Payload

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "riskLevel": "MEDIUM",
  "expiresAt": "2026-07-23T18:00:00Z"
}
```

禁止包含 Tool Credential 或完整 Tool Payload。

---

## PUB-010 ticket.execution_ready

### Mapping

```text
UC-12 / UC-15
SM-009 / SM-011
```

### Payload

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "policyDecision": "APPROVED",
  "toolExecutionId": "exec-500",
  "idempotencyKey": "tool-action:act-200"
}
```

### Security

- `idempotencyKey` 是业务执行键，不是 Secret。
- Credential 由 Tool Gateway 自己获取。
- Tool Gateway 必须验证 Approval 或 Policy Decision。

---

## PUB-011 ticket.verification_started

### Mapping

```text
UC-16 / UC-19
SM-010 / SM-014 / SM-032
```

### Payload

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-300",
  "resolutionAttemptId": "attempt-2",
  "toolExecutionId": "exec-500",
  "verificationType": "IDENTITY_LOGIN_CHECK",
  "attemptNumber": 1
}
```

---

## PUB-012 ticket.resolved

### Mapping

```text
UC-20
SM-018
```

### Payload

```json
{
  "resolutionCycleId": "cycle-1",
  "resolutionCode": "MFA_RESET_SUCCESSFUL",
  "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
  "verificationId": "ver-300",
  "resolvedBy": {
    "actorType": "AGENT",
    "actorId": "verification-agent"
  },
  "resolvedAt": "2026-07-23T17:30:00Z",
  "autoCloseAt": "2026-07-26T17:30:00Z"
}
```

不包含完整用户信息或未脱敏 Evidence。

---

## PUB-013 ticket.closed

### Mapping

```text
UC-23 / UC-24
SM-022 / SM-023
```

### Payload

```json
{
  "resolutionCycleId": "cycle-1",
  "closeReason": "REQUESTER_CONFIRMED",
  "closedBy": {
    "actorType": "EMPLOYEE",
    "actorIdHash": "sha256:..."
  },
  "closedAt": "2026-07-23T18:00:00Z"
}
```

---

## PUB-014 ticket.cancelled

### Mapping

```text
UC-26
SM-026
```

### Payload

```json
{
  "cancelReasonCode": "NO_LONGER_NEEDED",
  "cancelledBy": {
    "actorType": "EMPLOYEE",
    "actorIdHash": "sha256:..."
  },
  "invalidatedActionId": "act-200",
  "workflowCancellationRequested": true,
  "cancelledAt": "2026-07-23T17:00:00Z"
}
```

Consumers：

- Agent Runtime 取消 Workflow。
- Approval Service 失效未完成 Approval。
- Tool Gateway 仅取消尚未开始的 Action。

---

## PUB-015 ticket.reopened

### Mapping

```text
UC-25
SM-024 / SM-025
```

### Payload

```json
{
  "previousResolutionCycleId": "cycle-1",
  "newResolutionCycleId": "cycle-2",
  "newWorkflowId": "wf-9000",
  "newSlaCycleId": "sla-cycle-2",
  "reasonCode": "ISSUE_RECURRED",
  "reopenedBy": {
    "actorType": "EMPLOYEE",
    "actorIdHash": "sha256:..."
  },
  "reopenedAt": "2026-07-25T09:00:00Z"
}
```

---

## PUB-016 ticket.escalated

### Mapping

```text
UC-27
SM-017 / SM-020 / SM-029 / SM-033 / SM-034
```

### Payload

```json
{
  "targetType": "TEAM",
  "targetId": "SECURITY_SUPPORT",
  "reasonCode": "UNKNOWN_EXTERNAL_SIDE_EFFECT",
  "evidenceReferenceIds": [
    "exec-500",
    "ver-300"
  ],
  "automationRestricted": true,
  "escalatedAt": "2026-07-23T17:10:00Z"
}
```

---

## PUB-017 ticket.assigned

### Mapping

```text
UC-28
```

### Payload

```json
{
  "teamId": "IDENTITY_SUPPORT",
  "supportUserId": "support-42",
  "assignedBy": {
    "actorType": "IT_MANAGER",
    "actorIdHash": "sha256:..."
  },
  "assignedAt": "2026-07-23T17:10:00Z"
}
```

---

## PUB-018 ticket.message_added

### Mapping

```text
UC-05
```

### Payload

```json
{
  "messageId": "msg-21",
  "messageType": "USER_MESSAGE",
  "visibility": "REQUESTER_VISIBLE",
  "authorType": "EMPLOYEE",
  "attachmentCount": 1,
  "createdAt": "2026-07-23T16:45:00Z"
}
```

不包含 Message Body。

---

## PUB-019 ticket.approval_rejected

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "reasonCode": "INSUFFICIENT_JUSTIFICATION",
  "rejectedAt": "2026-07-23T16:50:00Z"
}
```

---

## PUB-020 ticket.approval_expired

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "expiredAt": "2026-07-23T18:00:00Z"
}
```

---

## PUB-021 ticket.automation_failed

### Mapping

```text
UC-29
SM-016 / SM-021 / SM-027
```

### Payload

```json
{
  "workflowId": "wf-7788",
  "failureReferenceId": "failure-99",
  "failureCategory": "DEPENDENCY_TIMEOUT",
  "retryable": true,
  "retryCount": 1,
  "failedAt": "2026-07-23T17:05:00Z"
}
```

不包含 Stack Trace。

---

## PUB-022 ticket.status_changed

### Payload

```json
{
  "fromStatus": "EXECUTING",
  "toStatus": "VERIFYING",
  "reasonCode": "TOOL_EXECUTION_SUCCEEDED",
  "actorType": "SERVICE",
  "sourceEventId": "evt-tool-500",
  "changedAt": "2026-07-23T17:20:00Z"
}
```

这是通用 Timeline / Analytics Event，不能替代更具体的业务 Event。

---

# 11. Ticket Workflow 消费事件总表

| ID | Event Type | Producer | Mapping |
|---|---|---|---|
| CON-001 | agent.workflow.started | Agent Runtime | UC-06 / SM-002 |
| CON-002 | agent.workflow.failed | Agent Runtime | UC-29 / SM-027 |
| CON-003 | ticket.classification.completed | Agent Runtime | UC-07 / SM-003 |
| CON-004 | agent.user_input_required | Agent Runtime | UC-08 |
| CON-005 | approval.requested | Policy & Approval | UC-11 / SM-008 |
| CON-006 | approval.granted | Policy & Approval | UC-12 / SM-011 |
| CON-007 | approval.rejected | Policy & Approval | UC-13 / SM-012 |
| CON-008 | approval.expired | Policy & Approval | UC-14 / SM-013 |
| CON-009 | policy.action_auto_approved | Policy & Approval | UC-15 / SM-009 |
| CON-010 | tool.execution.completed | Tool Gateway | UC-16 / SM-014 |
| CON-011 | tool.execution.failed | Tool Gateway | UC-17 / SM-015 |
| CON-012 | tool.execution.result_unknown | Tool Gateway | UC-18 / SM-017 |
| CON-013 | agent.resolution_candidate_ready | Agent Runtime | UC-19 / SM-010 |
| CON-014 | verification.completed | Verification Agent | UC-20 / UC-21 |

---

# 12. Consumed Event Contracts

## CON-001 agent.workflow.started

### Payload

```json
{
  "workflowId": "wf-7788",
  "workflowType": "IDENTITY_SUPPORT",
  "workflowVersion": "1.0.0",
  "startedAt": "2026-07-23T16:31:00Z"
}
```

### Guards

```text
Ticket status = NEW
No active Workflow
event.ticketId matches
```

### Idempotency

相同 EventId 或相同 WorkflowId 重复到达：

```text
ACK as idempotent success
```

---

## CON-002 agent.workflow.failed

### Payload

```json
{
  "workflowId": "wf-7788",
  "failureReferenceId": "failure-99",
  "failureCategory": "MODEL_TIMEOUT",
  "retryable": true,
  "retryCount": 1,
  "occurredAt": "2026-07-23T17:05:00Z"
}
```

### Guards

- Workflow 必须匹配 Active Workflow。
- 如果存在 Unknown Tool Side Effect，不能进入 FAILED，应 Escalate。
- 旧 Workflow Event 视为 `STALE_EVENT`。

---

## CON-003 ticket.classification.completed

### Payload

```json
{
  "workflowId": "wf-7788",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "HIGH",
  "confidence": 0.94,
  "source": "TRIAGE_AGENT",
  "classificationAttemptId": "classify-1"
}
```

### Guards

```text
Ticket status = TRIAGING
Workflow matches
Category/Subcategory valid
```

---

## CON-004 agent.user_input_required

### Payload

```json
{
  "workflowId": "wf-7788",
  "requestId": "req-88",
  "reasonCode": "NEED_DEVICE_INFORMATION",
  "message": "Please confirm whether your phone was replaced.",
  "resumeStatus": "INVESTIGATING"
}
```

### Security

`message` 是 Requester-visible 文本，必须通过 Content Safety 与 PII Review。

禁止包含 Prompt、Credential 或内部 Policy。

---

## CON-005 approval.requested

### Payload

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "riskLevel": "MEDIUM",
  "requestedAt": "2026-07-23T16:42:00Z",
  "expiresAt": "2026-07-23T18:00:00Z"
}
```

### Guards

- Ticket 为 INVESTIGATING。
- 无其他 Pending Action。
- Action 属于 Active Workflow。

---

## CON-006 approval.granted

### Payload

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "approvalId": "apr-900",
  "approvedByIdHash": "sha256:...",
  "approvedAt": "2026-07-23T16:50:00Z",
  "expiresAt": "2026-07-23T18:00:00Z",
  "toolExecutionId": "exec-500"
}
```

### Required Match

```text
ticketId
workflowId
actionId
actionType
approvalId
```

### Failure Behavior

| Condition | Behavior |
|---|---|
| Duplicate Event | ACK, no second transition |
| Approval expired | Reject business application, ACK + metric |
| Wrong Workflow | STALE_EVENT, ACK + audit |
| Wrong Action | ACTION_REFERENCE_MISMATCH, DLQ or security review |
| DB unavailable | Retry |
| Schema invalid | Immediate DLQ |

---

## CON-007 approval.rejected

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "reasonCode": "INSUFFICIENT_JUSTIFICATION",
  "rejectedAt": "2026-07-23T16:50:00Z"
}
```

---

## CON-008 approval.expired

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "approvalId": "apr-900",
  "expiredAt": "2026-07-23T18:00:00Z"
}
```

---

## CON-009 policy.action_auto_approved

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-210",
  "actionType": "REFRESH_USER_SESSION",
  "riskLevel": "LOW",
  "policyDecisionId": "policy-dec-300",
  "toolExecutionId": "exec-510",
  "decidedAt": "2026-07-23T16:55:00Z"
}
```

### Guards

- Policy Decision 必须为 `AUTO_APPROVED`。
- Action 属于 Active Workflow。
- Ticket 当前为 INVESTIGATING。

---

## CON-010 tool.execution.completed

### Payload

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "actionType": "RESET_DUO_ENROLLMENT",
  "toolExecutionId": "exec-500",
  "executionAttemptId": "exec-attempt-1",
  "result": "SUCCESS",
  "resultSummary": {
    "resultCode": "DUO_ENROLLMENT_RESET",
    "changed": true
  },
  "completedAt": "2026-07-23T17:15:00Z",
  "verificationId": "ver-300"
}
```

### Security

`resultSummary` 必须标准化和脱敏。

禁止返回：

```text
Admin Token
Raw Duo Response
Credential
Full User Directory Record
```

---

## CON-011 tool.execution.failed

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "toolExecutionId": "exec-500",
  "executionAttemptId": "exec-attempt-1",
  "result": "FAILED",
  "resultCertainty": "KNOWN_NO_SIDE_EFFECT",
  "errorCode": "TARGET_ACCOUNT_NOT_FOUND",
  "retryable": false,
  "failedAt": "2026-07-23T17:15:00Z"
}
```

只有：

```text
resultCertainty = KNOWN_NO_SIDE_EFFECT
```

才允许回到 INVESTIGATING。

---

## CON-012 tool.execution.result_unknown

```json
{
  "workflowId": "wf-7788",
  "actionId": "act-200",
  "toolExecutionId": "exec-500",
  "executionAttemptId": "exec-attempt-1",
  "resultCertainty": "UNKNOWN",
  "errorCode": "TIMEOUT_AFTER_REQUEST_SENT",
  "occurredAt": "2026-07-23T17:15:00Z"
}
```

Ticket 必须进入 ESCALATED，不能自动 Retry 或 Cancel。

---

## CON-013 agent.resolution_candidate_ready

```json
{
  "workflowId": "wf-7788",
  "resolutionAttemptId": "attempt-2",
  "resolutionCandidate": {
    "resolutionCode": "USER_GUIDANCE_SUCCESSFUL",
    "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
    "summary": "User completed re-enrollment."
  },
  "verificationId": "ver-300",
  "createdAt": "2026-07-23T17:20:00Z"
}
```

用于无 Tool 或人工修复后的 Verification。

---

## CON-014 verification.completed

### Success Payload

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-300",
  "resolutionAttemptId": "attempt-2",
  "attemptNumber": 1,
  "result": "SUCCESS",
  "evidenceSummary": {
    "checkType": "LOGIN_TEST",
    "resultCode": "AUTHENTICATION_SUCCEEDED"
  },
  "resolution": {
    "resolutionCode": "MFA_RESET_SUCCESSFUL",
    "rootCauseCode": "EXPIRED_DUO_ENROLLMENT",
    "summary": "Duo enrollment was reset and login verification succeeded."
  },
  "completedAt": "2026-07-23T17:30:00Z"
}
```

### Failure Payload

```json
{
  "workflowId": "wf-7788",
  "verificationId": "ver-301",
  "resolutionAttemptId": "attempt-2",
  "attemptNumber": 2,
  "result": "FAILURE",
  "failureCode": "LOGIN_STILL_FAILS",
  "retryable": true,
  "unsafe": false,
  "completedAt": "2026-07-23T17:35:00Z"
}
```

### Guards

必须匹配：

```text
ticketId
workflowId
verificationId
resolutionAttemptId
latest attempt
```

旧 Cycle 的 Verification 一律视为 Stale。

---

# 13. Consumer Processing Algorithm

```text
1. Receive Message
2. Validate Content-Type
3. Parse JSON
4. Validate Envelope Schema
5. Validate Event-specific Payload Schema
6. Validate Event Type and Major Version
7. Start / continue OpenTelemetry Span
8. Check Processed Event Store
9. Load Ticket
10. Validate Ticket, Workflow, Action and Attempt References
11. Validate source state and Business Invariants
12. Apply Use Case / State Transition
13. Save Ticket
14. Insert History
15. Insert Outbox Events
16. Insert Processed Event Record
17. Commit
18. ACK Message
```

---

# 14. ACK、Retry 与 DLQ 分类

## 14.1 ACK without Retry

以下情况通常 ACK，避免无限重试：

```text
Duplicate Event
Stale Event from old Workflow
Already-applied business result
Expired approval that cannot be applied
Invalid transition caused by late legitimate event
```

同时记录：

```text
audit log
metric
trace event
```

## 14.2 Retry

以下情况适合 Retry：

```text
Database temporarily unavailable
Optimistic Lock Conflict after re-evaluation
Transient internal dependency
Out-of-order event with expected predecessor likely arriving
```

## 14.3 Immediate DLQ

```text
Invalid JSON
Schema violation
Unknown Major Version
Missing required identity fields
Action or Ticket mismatch indicating possible corruption
Forbidden Secret field detected
```

---

# 15. Retry Policy

MVP 使用分级 Retry Queue：

```text
5 seconds
30 seconds
5 minutes
```

最多：

```text
3 retries
```

建议 Queue：

```text
ticket-workflow.retry.5s.v1
ticket-workflow.retry.30s.v1
ticket-workflow.retry.5m.v1
```

每次 Retry 增加 Header：

```text
x-opsmind-retry-count
x-opsmind-last-error-code
x-opsmind-first-failed-at
```

超过次数进入 DLQ。

---

# 16. Out-of-order Event 处理

示例：

```text
tool.execution.completed
先于
approval.granted
```

处理：

```text
1. 当前 Ticket 不在 EXECUTING
2. 检查 Event 是否可能合法但前置 Event 缺失
3. 进入 bounded retry
4. Retry 后仍缺失，进入 reconciliation
5. 最终进入 DLQ
```

禁止：

```text
直接把 Ticket 改成 VERIFYING
```

---

# 17. Processed Event Store

唯一键：

```text
consumerName + eventId
```

建议记录：

```text
consumerName
eventId
eventType
eventVersion
ticketId
workflowId
payloadHash
processedAt
processingResult
aggregateVersionAfter
```

`processingResult`：

```text
APPLIED
DUPLICATE
STALE
REJECTED_BUSINESS_RULE
```

Processed Event Record 与业务更新必须同事务提交。

---

# 18. Payload Hash

Consumer 计算 Canonical Payload Hash：

```text
SHA-256(canonical JSON)
```

用途：

- 检测相同 EventId 携带不同 Payload。
- 支持安全审计。
- 防止 Producer 错误重用 EventId。

如果：

```text
same eventId
different payloadHash
```

则：

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
→ Immediate DLQ
→ Security Alert
```

---

# 19. Transactional Outbox Contract

Ticket Workflow 发布 Event 时：

```text
BEGIN
Update Ticket
Insert Status History
Insert Outbox Event
COMMIT
```

Outbox Publisher：

```text
Read unpublished rows
Publish with Publisher Confirm
Mark published
```

Outbox Record 至少包含：

```text
outboxId
eventId
eventType
eventVersion
routingKey
aggregateId
aggregateVersion
ticketId
workflowId
payload
headers
createdAt
publishedAt
publishAttempts
lastPublishError
```

---

# 20. Publisher Confirm 与重复发布

只有收到 RabbitMQ Publisher Confirm 后，才能标记：

```text
publishedAt != null
```

如果服务在 Broker 接收后、标记 published 前崩溃：

```text
同一 Event 可能再次发布
```

因此 Consumer 必须依赖 `eventId` 幂等。

---

# 21. Manual Replay

## 21.1 Replay 未成功处理的原 Event

保留：

```text
original eventId
original payload
original occurredAt
```

增加 Header：

```text
x-opsmind-replayed = true
x-opsmind-replay-operator
x-opsmind-replay-time
```

## 21.2 修正后重新发送

如果 Payload 需要修改：

- 创建新的 `eventId`。
- `causationId` 指向原 EventId。
- 增加 `correctionOfEventId`。
- 不得修改原 Event 记录。

---

# 22. Schema Registry 目录

建议仓库结构：

```text
packages/event-schemas/
├── common/
│   └── event-envelope-v1.schema.json
├── ticket/
│   ├── published/
│   │   ├── ticket-created-v1.schema.json
│   │   ├── ticket-resolved-v1.schema.json
│   │   ├── ticket-closed-v1.schema.json
│   │   └── ...
│   └── consumed/
│       ├── approval-granted-v1.schema.json
│       ├── tool-execution-completed-v1.schema.json
│       ├── verification-completed-v1.schema.json
│       └── ...
└── examples/
```

---

# 23. Compatibility Rules

## Compatible

- 新增 Optional Field。
- 扩展 Description。
- 增加不影响旧 Consumer 的 Metadata。
- 修复 Example，不改变 Schema。

## Potentially Breaking

- Enum 增加新值。
- Field 从 Nullable 改为 Non-null。
- 数值单位变化。
- 时间语义变化。

## Breaking

- 删除 Required Field。
- 重命名 Field。
- 改变 Field Type。
- 改变 Event 业务语义。
- 将 Optional 改为 Required。
- 改变 ID 含义。

Breaking Change 必须使用新 Major Version 和 Routing Key。

---

# 24. Security 与 PII

## 24.1 Data Classification

```text
PUBLIC
INTERNAL
SENSITIVE
```

Event 不允许：

```text
SECRET
```

Secret 数据根本不能进入 Event Bus。

## 24.2 Sensitive 字段

可能包括：

- Requester Identifier Hash
- Ticket Title 摘要
- Resolution Summary
- Verification Evidence Summary

必须遵守最小化原则。

## 24.3 Log Redaction

Consumer / Publisher Log 不记录完整 Payload。

允许：

```text
eventId
eventType
ticketId
workflowId
aggregateVersion
payloadHash
```

---

# 25. OpenTelemetry

Producer Span：

```text
messaging.publish
```

Consumer Span：

```text
messaging.process
```

Attributes：

```text
messaging.system = rabbitmq
messaging.destination.name
messaging.operation.type
messaging.message.id
opsmind.event_type
opsmind.event_version
opsmind.ticket_id
opsmind.workflow_id
opsmind.aggregate_version
opsmind.processing_result
```

`ticketId` 只能作为 Trace Attribute / Structured Log Field，不作为 Prometheus Label。

---

# 26. Metrics

```text
ticket_event_published_total
ticket_event_publish_failed_total
ticket_event_consumed_total
ticket_event_processing_failed_total
ticket_event_duplicate_total
ticket_event_stale_total
ticket_event_out_of_order_total
ticket_event_schema_invalid_total
ticket_event_dlq_total
ticket_event_replayed_total
ticket_outbox_pending_count
ticket_outbox_oldest_age_seconds
```

推荐 Low-cardinality Label：

```text
event_type
producer
consumer
result
error_category
```

禁止 Label：

```text
ticket_id
workflow_id
event_id
requester_id
```

---

# 27. Contract Test 要求

## 27.1 Producer Contract Test

验证：

- Envelope Schema。
- Event-specific Schema。
- Routing Key。
- Required Headers。
- No Secret Fields。
- Example Payload。
- Domain Event Mapping。
- Aggregate Version。

## 27.2 Consumer Contract Test

验证：

- 正确 Event 可消费。
- 未知 Optional Field 被忽略。
- 缺失 Required Field 被拒绝。
- Unknown Major Version 进入 DLQ。
- Duplicate Event 幂等。
- Same EventId + Different Payload 被拒绝。
- Stale Workflow Event 不改变 Ticket。
- Out-of-order Event 进入 Retry。
- Business Update 与 Processed Event 同事务。

## 27.3 Compatibility Test

CI 对比 Main Branch Schema：

```text
additive-compatible
potentially-breaking
breaking
```

Breaking Change 必须失败，除非引入新 Major Version。

---

# 28. 关键测试案例

```text
shouldPublishTicketCreatedWithoutDescription
shouldPublishTicketResolvedWithVerificationReference
shouldConsumeApprovalGrantedExactlyOnce
shouldRejectApprovalForDifferentAction
shouldIgnoreLateVerificationFromOldWorkflow
shouldRetryOutOfOrderToolCompletedEvent
shouldDlqInvalidSchema
shouldDlqReusedEventIdWithDifferentPayload
shouldRollbackProcessedEventWhenTicketUpdateFails
shouldRepublishOutboxEventAfterPublisherCrash
shouldNotLogSensitivePayload
shouldPreserveTraceContextAcrossRabbitMq
```

---

# 29. Event 到 Use Case / State Machine 映射

| Event | Use Case | State Machine |
|---|---|---|
| agent.workflow.started | UC-06 | SM-002 |
| ticket.classification.completed | UC-07 | SM-003 |
| agent.user_input_required | UC-08 | SM-004 / SM-007 |
| approval.requested | UC-11 | SM-008 |
| approval.granted | UC-12 | SM-011 |
| approval.rejected | UC-13 | SM-012 |
| approval.expired | UC-14 | SM-013 |
| policy.action_auto_approved | UC-15 | SM-009 |
| tool.execution.completed | UC-16 | SM-014 |
| tool.execution.failed | UC-17 | SM-015 |
| tool.execution.result_unknown | UC-18 | SM-017 |
| agent.resolution_candidate_ready | UC-19 | SM-010 |
| verification.completed SUCCESS | UC-20 | SM-018 |
| verification.completed FAILURE | UC-21 | SM-019 / SM-020 |
| agent.workflow.failed | UC-29 | SM-027 |

---

# 30. 被拒绝的事件设计

## 30.1 通用 ticket.status_update_requested

拒绝，因为 Caller 可以绕过业务语义。

## 30.2 Event Payload 包含完整 Ticket

拒绝，因为泄漏 PII、耦合 Domain Model，并扩大 Payload。

## 30.3 使用 RabbitMQ Message Redelivery 代替业务幂等

拒绝，因为 Redelivery Flag 不能覆盖 Producer 重复发布和服务崩溃场景。

## 30.4 依赖 Queue 顺序而不检查 Workflow / Attempt

拒绝，因为跨 Queue、Retry 和并发情况下仍可能乱序。

## 30.5 Consumer 内直接调用外部 Tool

拒绝。Ticket Consumer 只更新 Ticket，并通过 Outbox 发布下一步 Event。

---

# 31. 验收标准

- [x] Canonical Envelope 已定义。
- [x] Event Type 与 Routing Key 规则已定义。
- [x] RabbitMQ Exchange、Queue 和 DLQ 已定义。
- [x] Ticket Workflow Published Events 已定义。
- [x] Ticket Workflow Consumed Events 已定义。
- [x] Payload 示例已定义。
- [x] Idempotency 与 Payload Hash 已定义。
- [x] Ordering 与 Retry 已定义。
- [x] Transactional Outbox 已定义。
- [x] Publisher Confirm 已定义。
- [x] Manual Replay 已定义。
- [x] Security、PII 与 Redaction 已定义。
- [x] OpenTelemetry 与 Metrics 已定义。
- [x] Contract Test 与 Compatibility 已定义。
- [x] Event 与 Use Case / State Machine 映射已定义。

---

# 32. 下一步

下一份文档：

```text
07-data-model_CN.md
07-data-model_EN.md
```

Data Model 将把本文档中的 Outbox、Processed Event、Ticket Aggregate、Message、History、SLA 和 Idempotency Record 映射到 PostgreSQL Schema。
