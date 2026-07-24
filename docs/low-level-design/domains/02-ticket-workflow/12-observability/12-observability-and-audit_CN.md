# OpsMind Ticket Workflow — 12 Observability and Audit

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Observability, Telemetry and Audit Design  
> **版本：** 1.0  
> **状态：** Proposed for Review  
> **依赖：** `03-state-machine_CN.md`、`04-use-cases_CN.md`、`05-api-contracts_CN.md`、`06-event-contracts_CN.md`、`07-data-model_CN.md`、`08-transaction-and-outbox_CN.md`、`09-concurrency-and-idempotency_CN.md`、`10-error-handling-and-reconciliation_CN.md`、`11-security-and-authorization_CN.md`  
> **标准：** OpenTelemetry、Prometheus、Structured JSON Logging  
> **建议路径：** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/12-observability-and-audit_CN.md`

---

## 1. 文档目的

本文档定义 Ticket Workflow 的 Trace、Log、Metric、Audit、Dashboard、Alert 和 Incident Debugging 规范。

本文档冻结：

- OpenTelemetry Resource
- Trace Context 与 Correlation
- Span Naming
- API、Transaction、Database、RabbitMQ、Outbox、Consumer、Scheduler Span
- Structured Logging Schema
- Log Level
- PII 与 Secret Redaction
- Prometheus Metric Naming
- Metric Cardinality
- SLI 与 SLO
- Dashboard
- Alert
- Audit Event
- Security Audit 与 Business Audit
- Golden Path Trace
- LangSmith 与 Agent Trace 关联
- Incident Debugging
- Retention
- Sampling
- Observability Test
- Failure Injection 验证

核心目标：

```text
任何 Ticket 的关键状态变化都可以通过 Trace、Log、Metric 和 Audit 被重建。
任何错误都可以定位到 Use Case、Event、Transaction 和依赖阶段。
任何用户或 Operator 行为都可以被安全审计，但不能泄漏 PII 或 Secret。
任何 Dashboard 和 Alert 都必须可执行，而不是只展示数据。
```

---

# 2. Observability Pillars

OpsMind 使用四个相互关联但职责不同的信号：

```text
Trace
Log
Metric
Audit
```

## 2.1 Trace

回答：

```text
一次请求或事件经过了哪些服务、阶段和依赖？
在哪一步变慢或失败？
```

## 2.2 Log

回答：

```text
某个具体组件在某一时刻发生了什么？
有哪些安全的上下文？
```

## 2.3 Metric

回答：

```text
系统整体是否健康？
错误率、延迟、积压、吞吐量是否异常？
```

## 2.4 Audit

回答：

```text
谁在什么时候对什么资源执行了什么动作？
是否允许？
结果是什么？
```

Audit 不是普通 Application Log，不能依赖 Log Retention 替代。

---

# 3. Telemetry Ownership

Ticket Workflow Service 负责产生：

- HTTP Server Telemetry
- Application Use Case Telemetry
- Domain Transition Telemetry
- PostgreSQL Client Telemetry
- Outbox Publisher Telemetry
- RabbitMQ Consumer Telemetry
- Scheduler Telemetry
- Authorization Decision Telemetry
- Business Audit Event
- Security Audit Reference

其他领域负责自己的 Telemetry，但必须继承 Trace Context。

---

# 4. OpenTelemetry Resource

每个 Service Instance 必须设置：

```text
service.name = ticket-workflow-service
service.namespace = opsmind
service.version = <build version>
service.instance.id = <instance id>
deployment.environment.name = local|ci|demo|staging|prod
cloud.region = <when applicable>
container.id = <when applicable>
k8s.pod.name = <when applicable>
```

禁止把以下值放入 Resource Attribute：

```text
ticketId
workflowId
userId
email
requesterId
```

Resource 是进程级上下文，不是请求级上下文。

---

# 5. Trace Context

## 5.1 W3C Trace Context

HTTP 使用：

```http
traceparent
tracestate
```

RabbitMQ Message Header 使用：

```text
traceparent
tracestate
```

## 5.2 Correlation ID

业务 Correlation：

```text
X-Correlation-Id
```

推荐值：

```text
displayId，例如 INC-2048
```

在 Ticket 创建前无法获得 Display ID 时，可先生成 Request Correlation ID，创建后切换到 Ticket Display ID 作为后续业务 Correlation。

## 5.3 Causation ID

用于标识当前操作由哪个 Command 或 Event 触发：

```text
causation_id
```

## 5.4 关键关联字段

```text
trace_id
span_id
correlation_id
ticket_id
workflow_id
event_id
command_id
aggregate_version
resolution_cycle_id
```

这些字段只能进入 Trace Attribute 或 Structured Log，不可作为高基数 Metric Label。

---

# 6. Trace Boundary

以下操作创建 Root 或 Consumer Span：

- HTTP Request
- RabbitMQ Event Consumption
- Scheduler Job
- Manual Recovery Command
- Outbox Publisher Poll Cycle
- Reconciliation Job

同一 HTTP Request 内部 Use Case 使用 Child Span。

RabbitMQ 消费使用 Producer Trace Context 创建 Consumer Span，并通过 Span Link 保留异步关系。

---

# 7. Span Naming Convention

格式：

```text
<domain>.<component>.<operation>
```

示例：

```text
ticket.api.create
ticket.usecase.cancel
ticket.transaction.commit
ticket.repository.load
ticket.outbox.claim
ticket.outbox.publish
ticket.consumer.approval_granted
ticket.scheduler.auto_close
ticket.authorization.evaluate
ticket.reconciliation.execute
```

禁止低价值命名：

```text
handle
process
run
method123
```

---

# 8. HTTP Server Span

推荐 Span：

```text
HTTP POST /api/v1/tickets
```

OpenTelemetry Semantic Attributes：

```text
http.request.method
http.route
http.response.status_code
url.scheme
server.address
server.port
user_agent.original? 仅在允许时
```

OpsMind Attributes：

```text
opsmind.use_case_id
opsmind.actor_type
opsmind.authorization_result
opsmind.ticket_status
opsmind.idempotency_replayed
opsmind.error_code
```

禁止：

```text
http.request.body
authorization header
raw idempotency key
requester email
message body
```

---

# 9. Application Use Case Span

每个 Command / Query 创建：

```text
ticket.usecase.<operation>
```

示例：

```text
ticket.usecase.create_ticket
ticket.usecase.add_message
ticket.usecase.cancel_ticket
ticket.usecase.reopen_ticket
ticket.usecase.start_verification
```

Attributes：

```text
opsmind.use_case_id
opsmind.command_type
opsmind.query_type
opsmind.actor_type
opsmind.status_before
opsmind.status_after
opsmind.version_before
opsmind.version_after
opsmind.result
opsmind.error_code
```

---

# 10. Domain Transition Span

状态变化创建：

```text
ticket.domain.transition
```

Attributes：

```text
opsmind.transition_id
opsmind.from_status
opsmind.to_status
opsmind.reason_code
opsmind.guard_result
opsmind.aggregate_version
```

Domain Span 不记录：

- Ticket Description
- Message
- Approval Approver Name
- Tool Raw Response

---

# 11. Transaction Span

```text
ticket.transaction
```

Attributes：

```text
opsmind.transaction_type
opsmind.use_case_id
db.system = postgresql
db.transaction.isolation_level = read_committed
db.transaction.retry_count
opsmind.rollback_reason
opsmind.outbox_event_count
opsmind.history_record_count
```

Events：

```text
transaction.begin
transaction.retry
transaction.commit
transaction.rollback
```

---

# 12. Database Spans

使用 OpenTelemetry JDBC Instrumentation。

Span Name：

```text
SELECT ticket.tickets
UPDATE ticket.tickets
INSERT ticket.outbox_events
```

允许 Attribute：

```text
db.system
db.namespace
db.operation.name
db.collection.name
server.address
db.response.status_code
```

禁止记录完整 SQL 和 Bind Parameter，尤其是：

- Title
- Description
- Message Body
- Requester ID
- Token

生产默认关闭 SQL Statement Capture，必要时只启用参数化 Template。

---

# 13. Outbox Publisher Trace

## Poll Span

```text
ticket.outbox.poll
```

Attributes：

```text
opsmind.batch_size_requested
opsmind.batch_size_claimed
opsmind.publisher_instance
opsmind.oldest_pending_age_seconds
```

## Claim Span

```text
ticket.outbox.claim
```

## Publish Span

```text
ticket.outbox.publish
```

Attributes：

```text
messaging.system = rabbitmq
messaging.destination.name = opsmind.events
messaging.operation.type = publish
messaging.message.id
opsmind.event_type
opsmind.event_version
opsmind.publish_attempt
opsmind.routing_key
opsmind.confirm_result
```

`messaging.message.id` 可以进入 Trace，但不能作为 Metric Label。

---

# 14. RabbitMQ Consumer Trace

Span：

```text
ticket.consumer.<event_type_normalized>
```

示例：

```text
ticket.consumer.approval_granted
ticket.consumer.tool_execution_completed
ticket.consumer.verification_completed
```

Attributes：

```text
messaging.system = rabbitmq
messaging.destination.name
messaging.operation.type = process
messaging.message.id
messaging.rabbitmq.routing_key
opsmind.event_type
opsmind.event_version
opsmind.producer
opsmind.consumer_name
opsmind.processing_result
opsmind.classification
opsmind.retry_count
opsmind.broker_disposition
```

Span Event：

```text
event.schema_validated
event.duplicate_detected
event.classified_stale
event.classified_out_of_order
event.applied
event.sent_to_dlq
```

---

# 15. Span Link for Asynchronous Flow

Outbox Publisher 的 Publish Span 与原 Business Transaction Span 使用：

```text
Span Link
```

Consumer Span 与 Producer Span 使用 Message Trace Context 关联。

在无法保留父子关系时：

```text
Consumer Span = New Root
+
Link to Producer Span
```

这样避免错误地让长时间异步流程表现为单个持续数小时的 Parent Span。

---

# 16. Scheduler Trace

Scheduler Root Span：

```text
ticket.scheduler.auto_close
ticket.scheduler.sla_scan
ticket.scheduler.outbox_cleanup
ticket.scheduler.integrity_scan
```

Attributes：

```text
opsmind.job_name
opsmind.job_run_id
opsmind.candidate_count
opsmind.processed_count
opsmind.success_count
opsmind.failure_count
opsmind.duration_ms
```

每个 Ticket 的具体处理可以创建 Child Span，但在 Batch 很大时需采样或限制数量。

---

# 17. Reconciliation Trace

```text
ticket.reconciliation.open
ticket.reconciliation.investigate
ticket.reconciliation.recover
ticket.reconciliation.verify
ticket.reconciliation.resolve
```

Attributes：

```text
opsmind.reconciliation_type
opsmind.reconciliation_status
opsmind.priority
opsmind.recovery_outcome
opsmind.operator_type
opsmind.approval_required
opsmind.verification_result
```

不得记录 Evidence Body，只记录 Evidence Reference Count 和 ID Reference。

---

# 18. LangSmith Correlation

Agent Runtime 使用 LangSmith。

Ticket Service 不把完整 Ticket 数据复制到 LangSmith。

关联字段：

```text
ticket_trace_id
ticket_id
workflow_id
resolution_cycle_id
agent_run_id
```

规则：

- Ticket Service Trace 记录 `agent_run_id`。
- Agent Runtime LangSmith Metadata 记录 `ticket_trace_id` 和 `ticket_id`。
- Message Body、Description 和 PII 在写入 LangSmith 前 Redact。
- LangSmith Retention 独立于 Ticket Database Retention。
- 生产环境默认关闭完整 Prompt / Response Capture，或使用严格脱敏。

---

# 19. Structured Logging

所有 Log 使用 JSON。

Canonical Schema：

```json
{
  "timestamp": "2026-07-23T17:30:00.000Z",
  "level": "INFO",
  "service": "ticket-workflow-service",
  "environment": "demo",
  "instanceId": "ticket-1",
  "logger": "TicketEventConsumer",
  "message": "Ticket event processed",
  "traceId": "8f03d65a...",
  "spanId": "01ab...",
  "correlationId": "INC-2048",
  "ticketId": "01J...",
  "workflowId": "wf-7788",
  "eventId": "evt-500",
  "eventType": "approval.granted",
  "useCaseId": "UC-12",
  "errorCode": null,
  "result": "APPLIED",
  "durationMs": 42
}
```

---

# 20. Log Level Policy

## TRACE

仅本地调试，生产默认关闭。

## DEBUG

安全的内部状态和决策，不记录敏感数据。

## INFO

正常业务里程碑：

- Ticket Created
- Transition Applied
- Event Processed
- Outbox Published
- Reconciliation Resolved

## WARN

可恢复异常：

- Retry
- Stale Event Spike
- Confirm Timeout
- Authorization Denial
- Out-of-order Event

## ERROR

单次操作失败，需要关注：

- Retry Exhausted
- DLQ
- Dependency Permanent Failure
- Data Integrity Error

## FATAL

服务无法安全启动或继续：

- Migration Failure
- Invalid Security Configuration
- Schema Ownership Failure

---

# 21. Log Message Rules

Log Message 必须描述事实：

```text
"Ticket transition applied"
"Outbox publish confirm timed out"
"Approval event rejected because workflow reference is stale"
```

禁止：

```text
"Something went wrong"
"Error!"
"Failed"
```

动态数据放入 Structured Field，不拼接到 Message。

---

# 22. Log Redaction

禁止记录：

```text
Authorization
JWT
Idempotency-Key
Password
Access Token
Refresh Token
API Key
Private Key
Session Cookie
Ticket Description
Message Body
Raw Prompt
Raw LLM Response
Raw Tool Response
```

可记录：

```text
payloadHash
requestHash
bodyLength
attachmentCount
classification
referenceId
```

---

# 23. Redaction Pipeline

日志与 Trace 写出前经过：

```text
Field Allowlist
→ Secret Pattern Detection
→ PII Redaction
→ Length Limit
→ Structured Encoding
```

如果 Redaction Pipeline 失败：

```text
Drop unsafe field
Emit redaction failure metric
Never fall back to raw value
```

Metric：

```text
ticket_telemetry_redaction_failure_total
```

---

# 24. Log Sampling

以下高频成功 Log 可采样：

- Read Ticket Success
- List Tickets Success
- Duplicate Event Success
- Outbox Poll Empty

以下不能采样：

- Security Alert
- Authorization Denial for High-risk Action
- DLQ
- Data Integrity Failure
- Manual Recovery
- Compensation
- EventId Payload Conflict

---

# 25. Metric Naming Convention

格式：

```text
opsmind_ticket_<subject>_<unit_or_type>
```

Prometheus 示例：

```text
opsmind_ticket_requests_total
opsmind_ticket_request_duration_seconds
opsmind_ticket_transitions_total
opsmind_ticket_outbox_pending
opsmind_ticket_outbox_oldest_age_seconds
```

Counter 以 `_total` 结尾。

Duration 使用 Seconds。

Gauge 不使用 `_total`。

---

# 26. Metric Cardinality Rules

允许 Label：

```text
operation
http_method
http_status_class
status
from_status
to_status
event_type
result
error_code
error_category
consumer
producer
queue
priority
environment
```

禁止 Label：

```text
ticket_id
display_id
workflow_id
event_id
command_id
requester_id
operator_id
trace_id
idempotency_key
email
IP
```

原则：

```text
Label Value 必须来自有限集合。
```

---

# 27. API Metrics

```text
opsmind_ticket_http_requests_total
opsmind_ticket_http_request_duration_seconds
opsmind_ticket_http_errors_total
opsmind_ticket_rate_limited_total
opsmind_ticket_authorization_denied_total
opsmind_ticket_idempotency_replay_total
```

Labels：

```text
route
method
status_class
operation
result
```

不使用原始 URL，因为 URL 中可能包含 TicketId。

---

# 28. Domain Metrics

```text
opsmind_ticket_created_total
opsmind_ticket_transition_total
opsmind_ticket_transition_rejected_total
opsmind_ticket_resolved_total
opsmind_ticket_closed_total
opsmind_ticket_cancelled_total
opsmind_ticket_reopened_total
opsmind_ticket_escalated_total
opsmind_ticket_resolution_duration_seconds
opsmind_ticket_time_in_status_seconds
```

Labels：

```text
from_status
to_status
reason_code
priority
application_code
result
```

`application_code` 必须受控，不能允许任意用户输入作为 Label。

---

# 29. Transaction Metrics

```text
opsmind_ticket_transaction_total
opsmind_ticket_transaction_duration_seconds
opsmind_ticket_transaction_rollback_total
opsmind_ticket_transaction_retry_total
opsmind_ticket_optimistic_lock_conflict_total
opsmind_ticket_database_deadlock_total
```

Labels：

```text
transaction_type
result
error_category
```

---

# 30. Event Metrics

```text
opsmind_ticket_event_consumed_total
opsmind_ticket_event_processing_duration_seconds
opsmind_ticket_event_duplicate_total
opsmind_ticket_event_stale_total
opsmind_ticket_event_out_of_order_total
opsmind_ticket_event_schema_invalid_total
opsmind_ticket_event_reference_corruption_total
opsmind_ticket_event_terminal_conflict_total
opsmind_ticket_event_dlq_total
opsmind_ticket_event_replayed_total
```

Labels：

```text
event_type
consumer
producer
classification
result
```

---

# 31. Outbox Metrics

```text
opsmind_ticket_outbox_pending
opsmind_ticket_outbox_oldest_age_seconds
opsmind_ticket_outbox_claimed_total
opsmind_ticket_outbox_published_total
opsmind_ticket_outbox_publish_duration_seconds
opsmind_ticket_outbox_publish_failed_total
opsmind_ticket_outbox_publish_retry_total
opsmind_ticket_outbox_unroutable_total
opsmind_ticket_outbox_lock_recovered_total
```

---

# 32. Scheduler Metrics

```text
opsmind_ticket_scheduler_runs_total
opsmind_ticket_scheduler_duration_seconds
opsmind_ticket_scheduler_candidates
opsmind_ticket_scheduler_processed_total
opsmind_ticket_scheduler_failed_total
```

Labels：

```text
job_name
result
```

---

# 33. Security Metrics

```text
opsmind_ticket_authentication_failure_total
opsmind_ticket_authorization_denied_total
opsmind_ticket_cross_queue_denied_total
opsmind_ticket_sensitive_read_total
opsmind_ticket_secret_detected_total
opsmind_ticket_event_producer_rejected_total
opsmind_ticket_recovery_authorization_denied_total
opsmind_ticket_suspicious_enumeration_total
```

---

# 34. Reconciliation Metrics

```text
opsmind_ticket_reconciliation_open
opsmind_ticket_reconciliation_opened_total
opsmind_ticket_reconciliation_resolved_total
opsmind_ticket_reconciliation_failed_total
opsmind_ticket_reconciliation_age_seconds
opsmind_ticket_reconciliation_manual_review_total
opsmind_ticket_compensation_requested_total
opsmind_ticket_compensation_completed_total
opsmind_ticket_compensation_failed_total
```

Labels：

```text
type
priority
status
outcome
```

---

# 35. Histogram Buckets

## HTTP / Use Case Duration

建议：

```text
0.005
0.01
0.025
0.05
0.1
0.25
0.5
1
2.5
5
10
```

## Event Processing

```text
0.01
0.025
0.05
0.1
0.25
0.5
1
2
5
```

## Ticket Resolution Duration

```text
60
300
900
3600
14400
43200
86400
259200
604800
```

Buckets 必须根据实际分布调整。

---

# 36. Exemplars

Prometheus Histogram 可使用 Trace Exemplar：

```text
trace_id
```

用于从高延迟 Bucket 跳转到具体 Trace。

Exemplar 不作为普通 Label。

---

# 37. SLI

## 37.1 API Availability

```text
successful eligible requests
/
total eligible requests
```

排除：

- 4xx Validation
- Authorization Denial
- Client Cancelled Request

包括：

- 5xx
- 503
- Timeout

## 37.2 API Latency

```text
p95 / p99 of server request duration
```

按 Route Template 计算。

## 37.3 Event Processing Success

```text
APPLIED + DUPLICATE + STALE
/
valid consumed events
```

DLQ、Retry Exhausted 为失败。

## 37.4 Outbox Freshness

```text
oldest unpublished event age
```

## 37.5 Ticket Transition Correctness

```text
successful legal transitions
/
all attempted transitions excluding expected business rejection
```

## 37.6 Audit Completeness

```text
audited high-risk operations
/
all committed high-risk operations
```

目标必须为 100%。

---

# 38. SLO

MVP / Demo 建议：

## API

```text
Availability: 99.5% monthly
p95 read latency: < 300ms
p95 command latency: < 800ms
p99 command latency: < 2s
```

## Event

```text
99% valid events processed or safely classified within 60s
99.9% within 5m
```

## Outbox

```text
99% events published within 10s
oldest pending < 5m
```

## Audit

```text
100% high-risk committed operations have audit record
```

## Resolution Pipeline

```text
99% tool success events enter verification within 60s
```

这些 SLO 是项目目标，生产部署前需根据真实业务重新批准。

---

# 39. Error Budget

例如 API Availability SLO 99.5%：

```text
月度 Error Budget ≈ 0.5%
```

消耗策略：

- 25%：Warning
- 50%：暂停非必要 Release
- 75%：只允许 Reliability Fix
- 100%：进入 Incident / Change Freeze

---

# 40. Dashboard Set

## 40.1 Ticket Workflow Overview

展示：

- Request Rate
- Error Rate
- p95 / p99 Latency
- Ticket Created / Resolved / Closed
- Current Ticket Status Distribution
- Reopen Rate
- Escalation Rate

## 40.2 Event and Outbox

- Event Throughput
- Processing Result
- Duplicate / Stale / Out-of-order
- Retry
- DLQ
- Outbox Pending
- Oldest Pending
- Publish Failure
- Confirm Latency

## 40.3 Database and Transaction

- Transaction Duration
- Rollback
- Deadlock
- Optimistic Conflict
- Connection Pool
- Slow Query
- Lock Wait

## 40.4 Security

- Authentication Failure
- Authorization Denial
- Cross-queue Denial
- Sensitive Read
- Secret Detection
- Wrong Producer
- Recovery Denial

## 40.5 Reconciliation

- Open Cases
- Age by Priority
- Recovery Outcome
- Manual Review Backlog
- Compensation Success / Failure

## 40.6 SLA

- Response Due
- Resolution Due
- Breach Rate
- Time in Status
- Auto-close

---

# 41. Golden Signals

Ticket Workflow Golden Signals：

```text
Latency
Traffic
Errors
Saturation
```

## Latency

- HTTP
- Transaction
- Event Processing
- Outbox Publish
- Database Query

## Traffic

- Requests
- Commands
- Events
- Scheduler Candidates

## Errors

- 5xx
- DLQ
- Retry Exhausted
- Data Integrity
- Security Error

## Saturation

- DB Pool
- RabbitMQ Queue Depth
- Thread Pool
- Outbox Backlog
- CPU / Memory

---

# 42. Alert Design Principles

每个 Alert 必须包含：

```text
What is wrong
Why it matters
Likely causes
Dashboard link
Runbook link
First safe action
Severity
Owner
```

禁止无上下文 Alert：

```text
"Error rate high"
```

---

# 43. Critical Alerts

## Outbox Stuck

条件：

```text
opsmind_ticket_outbox_oldest_age_seconds > 300
```

持续：

```text
5 minutes
```

## DLQ Growth

```text
rate(opsmind_ticket_event_dlq_total[5m]) > 0
```

对 Security / Integrity Event 立即告警。

## Audit Missing

```text
committed high-risk operation
without matching audit record
```

必须 Critical。

## Security

- Secret Detected
- Wrong Producer
- EventId Payload Conflict
- Cross-Ticket Corruption
- Unauthorized DB Write

---

# 44. Warning Alerts

- API p95 超过目标
- Event Out-of-order 上升
- Optimistic Conflict 异常上升
- Duplicate Rate 突增
- Reconciliation Age 超过 SLA
- Rate-limit Spike
- DB Pool 使用率高
- Scheduler Failure

---

# 45. Alert Deduplication

Alert Label 只使用低基数字段：

```text
service
environment
alertname
error_category
event_type
queue
```

TicketId 不作为 Alert Group Label。

单个 Ticket 问题通过 Trace / Log 查询定位。

---

# 46. Audit Model

Audit 分为：

```text
Business Audit
Security Audit
Recovery Audit
Sensitive Read Audit
```

## Business Audit

例如：

- Ticket Created
- Status Transition
- Assignment
- Cancel
- Reopen
- Close

## Security Audit

例如：

- Authorization Denial
- Cross-queue Access
- Wrong Producer
- Secret Detection

## Recovery Audit

例如：

- Event Replay
- Correction Event
- Compensation
- Data Repair

## Sensitive Read Audit

例如：

- Auditor 查看敏感 Timeline
- Admin 跨 Queue 查看
- Reconciliation Evidence 查看

---

# 47. Canonical Audit Event

```json
{
  "auditId": "audit-100",
  "auditVersion": "1.0",
  "occurredAt": "2026-07-23T17:30:00Z",
  "auditType": "BUSINESS_ACTION",
  "action": "TICKET_CANCELLED",
  "decision": "ALLOWED",
  "actor": {
    "actorType": "EMPLOYEE",
    "actorId": "user-123",
    "clientId": "opsmind-web",
    "authenticationLevel": "MFA"
  },
  "resource": {
    "resourceType": "TICKET",
    "resourceId": "01J...",
    "displayId": "INC-2048"
  },
  "context": {
    "ticketStatusBefore": "INVESTIGATING",
    "ticketStatusAfter": "CANCELLED",
    "reasonCode": "NO_LONGER_NEEDED",
    "queueId": "IDENTITY_SUPPORT"
  },
  "traceId": "8f03...",
  "commandId": "cmd-200",
  "outcome": "SUCCESS",
  "dataClassification": "SENSITIVE"
}
```

---

# 48. Audit Atomicity

高风险业务操作的本地 Audit Reference 必须与业务事务同事务提交。

示例：

```text
Update Ticket
Insert Status History
Insert Security Audit Record
Insert Outbox Audit Event
COMMIT
```

Central Audit Platform 可以异步消费，但本地 Audit Reference 不能丢失。

---

# 49. Audit Immutability

Audit Table：

- Append-only
- Application Role 无普通 UPDATE / DELETE
- Retention 受政策控制
- Replay 不修改原 Audit
- Correction 追加新 Audit

未来生产可使用：

- WORM Storage
- Hash Chain
- Signed Audit Batch

MVP 至少使用数据库权限和 Append-only 约束。

---

# 50. Audit Hash Chain

生产增强建议：

```text
record_hash =
SHA-256(
  canonical audit record
  + previous_record_hash
)
```

用于检测删除或修改。

MVP 可先保留字段：

```text
previous_hash
record_hash
```

---

# 51. Audit Data Minimization

Audit 不保存：

- Message Body
- Ticket Description
- Secret
- Raw JWT
- Full Event Payload
- Raw IP
- Raw User Agent

允许：

- Actor ID
- Action
- Decision
- Resource ID
- Scope
- Queue
- Reason Code
- Trace ID
- Snapshot Hash

---

# 52. Audit Retention

建议：

| Audit Type | Retention |
|---|---:|
| Business Audit | 2 years |
| Security Audit | 2–7 years，按政策 |
| Recovery Audit | 2–7 years |
| Sensitive Read Audit | 2 years |
| Operational Log | 30–90 days |
| Trace | 7–30 days |
| Metric | 13 months |

最终 Retention 必须由组织合规政策批准。

---

# 53. Trace Sampling

## Local / CI

```text
100%
```

## Demo / Staging

```text
20–100%
```

## Production

Head Sampling：

```text
5–10%
```

Tail Sampling 强制保留：

- Error
- Latency > threshold
- DLQ
- Security Alert
- Reconciliation
- Compensation
- High-risk Admin Action
- Unknown Tool Result
- Event Conflict

---

# 54. Sampling Consistency

同一 Trace 的 Span 应保持一致采样决策。

跨 RabbitMQ 传播 Trace Flags。

即使 Trace 未采样，Audit 仍必须记录。

Metric 不受 Trace Sampling 影响。

---

# 55. Log and Trace Retention

环境建议：

| Environment | Log | Trace |
|---|---:|---:|
| local | 7 days | 3 days |
| ci | 7 days | 7 days |
| demo | 30 days | 14 days |
| staging | 30 days | 14 days |
| prod | 30–90 days | 14–30 days |

Security Incident 可以 Legal Hold。

---

# 56. Golden Path Trace

Golden Path：

```text
Create Ticket
→ Triage
→ Investigate
→ Approval
→ Execute Tool
→ Verify
→ Resolve
→ Close
```

预期 Span：

```text
HTTP POST /tickets
ticket.usecase.create_ticket
ticket.transaction
INSERT tickets
INSERT outbox
ticket.outbox.publish
ticket.consumer.workflow_started
ticket.consumer.classification_completed
ticket.consumer.approval_granted
ticket.consumer.tool_execution_completed
ticket.consumer.verification_completed
ticket.usecase.resolve_ticket
ticket.scheduler.auto_close
```

关键关联：

```text
ticketId
workflowId
resolutionCycleId
actionId
approvalId
toolExecutionId
verificationId
```

---

# 57. Incident Debugging Workflow

## Step 1：确认影响

查看：

- Error Rate
- Affected Operation
- Environment
- Queue / Event Type
- Time Window

## Step 2：定位 Trace

通过：

```text
traceId
correlationId
displayId
eventId
```

## Step 3：验证业务状态

查询：

- Ticket Snapshot
- Status History
- Resolution Cycle
- Pending Action
- Processed Event
- Outbox Event

## Step 4：检查跨服务

- Agent Run
- Approval Record
- Tool Execution
- Verification
- RabbitMQ Queue / DLQ

## Step 5：决定恢复

- Retry
- Mark Stale
- Replay
- Correction
- Escalation
- Reconciliation

## Step 6：验证

- 状态合法
- History 连续
- Outbox 清空
- Event 已处理
- 用户影响解除

---

# 58. Incident Debugging 安全规则

禁止：

- 在群聊粘贴完整 Ticket Description
- 在 Issue 中粘贴 Token
- 在 Dashboard 展示 Message Body
- 为了 Debug 临时关闭 Authorization
- 直接 SQL 修改状态
- 在生产开启完整 Prompt Capture

---

# 59. Query Examples

## 查找某 Ticket Trace

```text
ticket_id = "01J..."
```

## 查找某 Event

```text
event_id = "evt-500"
```

## 查找状态转换失败

```text
error_code = "INVALID_STATE_TRANSITION"
AND use_case_id = "UC-26"
```

## 查找 Outbox 问题

```text
event_type = "ticket.resolved"
AND confirm_result = "TIMEOUT"
```

具体查询语法依赖 Backend，不在本文档绑定 Vendor。

---

# 60. Observability Backend

MVP 可选：

```text
OpenTelemetry Collector
Prometheus
Grafana
Loki
Tempo / Jaeger
```

LangSmith 只用于 Agent Runtime 和 LLM Trace，不替代系统级 OpenTelemetry。

架构：

```text
Application
→ OTel SDK
→ OTel Collector
→ Metrics / Logs / Traces Backends
```

---

# 61. OpenTelemetry Collector

Collector 负责：

- Batch
- Retry
- Resource Enrichment
- Redaction
- Attribute Drop
- Tail Sampling
- Export

禁止每个服务直接维护多个 Vendor-specific Exporter。

---

# 62. Telemetry Failure Behavior

Telemetry Backend 不可用时：

- 业务请求不得失败。
- 使用内存有界 Batch。
- 超过 Buffer 后 Drop Telemetry。
- 增加 Local Self-metric。
- 不无限阻塞业务线程。

Self-metrics：

```text
otel_export_failed_total
otel_dropped_spans_total
otel_dropped_logs_total
```

Audit 写入失败例外：

```text
高风险业务操作必须 Fail Closed 或事务 Rollback
```

因为 Audit Completeness 是业务要求。

---

# 63. Audit Delivery

本地 Audit Record 与业务事务提交。

异步发布：

```text
ticket.audit_recorded.v1
```

Central Audit Consumer 可以重复消费，但使用：

```text
auditId
```

幂等。

Audit Event Payload 仍需最小化。

---

# 64. Dashboard Access Control

Dashboard 按角色控制：

- Developer：技术指标
- Support Manager：业务和 SLA
- Security：安全 Dashboard
- Auditor：Audit Dashboard
- Executive：聚合 KPI

普通用户不可访问内部 Dashboard。

包含 Sensitive Data 的 Panel 默认关闭或 Redact。

---

# 65. Metric Integrity

Metric 不作为业务 Source of Truth。

例如：

```text
resolved_total
```

不能替代数据库中 Resolution Record。

Metric 丢失不应影响 Ticket 状态。

---

# 66. Observability Tests

## Unit

```text
shouldAddUseCaseIdToSpan
shouldNotAddTicketDescriptionToSpan
shouldRedactTokenFromLog
shouldRejectHighCardinalityMetricLabel
shouldCreateAuditRecordForHighRiskAction
shouldNotSampleOutSecurityIncidentTrace
```

## Integration

```text
shouldPropagateTraceparentThroughRabbitMq
shouldLinkOutboxPublishToBusinessTrace
shouldEmitDuplicateEventMetric
shouldRecordRollbackReason
shouldWriteAuditAtomicallyWithCancel
shouldExposePrometheusMetrics
shouldExportStructuredJsonLogs
```

## End-to-End

```text
shouldTraceGoldenPathAcrossServices
shouldLocateTicketByCorrelationId
shouldLinkAgentRunToTicketTrace
shouldAlertWhenOutboxOldestAgeExceedsThreshold
shouldPreserveAuditWhenTraceIsUnsampled
```

---

# 67. Cardinality Tests

CI 应检查：

- Metric Label Name Allowlist
- Label Value 来源
- 禁止 TicketId Label
- 禁止 URL Path 中动态 ID 作为 Label
- `application_code` 必须来自受控 Catalog
- `error_code` 必须来自稳定枚举

---

# 68. Redaction Tests

注入：

```text
Bearer eyJ...
-----BEGIN PRIVATE KEY-----
password=secret
api_key=abc
MFA recovery code
```

验证：

- Log 中不出现
- Trace 中不出现
- Metric 中不出现
- LangSmith 中不出现
- Audit 中不出现原值
- Security Metric 增加

---

# 69. Failure Injection Tests

```text
FAIL_AFTER_TICKET_UPDATE
FAIL_OUTBOX_INSERT
FAIL_BEFORE_PUBLISH_CONFIRM
FAIL_AFTER_CONSUMER_COMMIT_BEFORE_ACK
OTEL_COLLECTOR_UNAVAILABLE
PROMETHEUS_SCRAPE_FAILURE
AUDIT_INSERT_FAILURE
```

验证：

- Transaction Rollback 可见
- Duplicate Event 可见
- Outbox Backlog 告警
- Telemetry Failure 不阻塞普通业务
- Audit Failure 阻止高风险 Commit

---

# 70. Runbook Requirements

每个 Critical Alert 必须有 Runbook：

```text
Meaning
User Impact
Detection Query
Dashboard
Likely Causes
Safe First Actions
Unsafe Actions
Recovery
Verification
Escalation Owner
Closure Criteria
```

---

# 71. Data Model Increment

后续建议更新 `07-data-model`：

```text
ticket.security_audit_records
ticket.business_audit_records
ticket.sensitive_read_audit
```

或统一为：

```text
ticket.audit_records
```

关键字段：

```text
audit_id
audit_version
audit_type
action
decision
actor_type
actor_id
client_id
resource_type
resource_id
reason_code
trace_id
command_id
before_snapshot_hash
after_snapshot_hash
safe_metadata
previous_hash
record_hash
occurred_at
```

---

# 72. Event Contract Increment

建议增加：

```text
ticket.audit_recorded.v1
ticket.security_alert_raised.v1
ticket.reconciliation_opened.v1
ticket.reconciliation_resolved.v1
```

Audit Event 不能包含 Message Body 或 Secret。

---

# 73. Acceptance Criteria

- [x] Trace、Log、Metric 和 Audit 职责已定义。
- [x] OpenTelemetry Resource 和 Trace Context 已定义。
- [x] HTTP、Use Case、Domain、Transaction、DB、Outbox、Consumer、Scheduler 和 Reconciliation Span 已定义。
- [x] Async Span Link 已定义。
- [x] LangSmith Correlation 与 Redaction 已定义。
- [x] Structured Logging Schema 与 Log Level 已定义。
- [x] Log / Trace Redaction 已定义。
- [x] Metric Naming 与 Cardinality 已定义。
- [x] API、Domain、Transaction、Event、Outbox、Scheduler、Security 和 Reconciliation Metric 已定义。
- [x] SLI、SLO 和 Error Budget 已定义。
- [x] Dashboard 与 Alert 已定义。
- [x] Audit Model、Atomicity、Immutability、Retention 和 Hash Chain 已定义。
- [x] Sampling、Retention 和 Telemetry Failure Behavior 已定义。
- [x] Golden Path Trace 与 Incident Debugging 已定义。
- [x] Observability、Cardinality、Redaction 和 Failure Injection Test 已定义。

---

# 74. 下一步

下一份文档：

```text
13-package-and-class-design_CN.md
13-package-and-class-design_EN.md
```

该文档将把前十二份设计映射为 Java / Spring Boot 的 Package、Class、Interface、Controller、Application Service、Domain Model、Repository、Event Consumer、Outbox Publisher、Security 和 Observability 代码结构。
