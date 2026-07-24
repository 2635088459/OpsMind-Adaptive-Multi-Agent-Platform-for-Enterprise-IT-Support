# OpsMind Ticket Workflow — 12 Observability and Audit

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level Observability, Telemetry, and Audit Design  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependencies:** `03-state-machine/README_EN.md`, `04-use-cases/README_EN.md`, `05-api-contracts/README_EN.md`, `06-event-contracts/README_EN.md`, `07-data-model/README_EN.md`, `08-transaction-and-outbox/README_EN.md`, `09-concurrency-and-idempotency/README_EN.md`, `10-failure-handling/README_EN.md`, `11-security/README_EN.md`  
> **Standards:** OpenTelemetry, Prometheus, Structured JSON Logging  
> **Recommended Path:** `docs/low-level-design/domains/02-ticket-workflow/12-observability/README_EN.md`

---

## 1. Purpose

This document defines tracing, logging, metrics, audit, dashboards, alerts, and incident debugging for Ticket Workflow.

It freezes:

- OpenTelemetry resources
- Trace context and correlation
- Span naming
- API, transaction, database, RabbitMQ, Outbox, consumer, and scheduler spans
- Structured logging schema
- Log levels
- PII and secret redaction
- Prometheus metric naming
- Metric cardinality
- SLIs and SLOs
- Dashboards
- Alerts
- Audit events
- Security and business audit
- Golden Path tracing
- LangSmith correlation
- Incident debugging
- Retention
- Sampling
- Observability testing
- Failure-injection validation

Core goals:

```text
Every important Ticket state change can be reconstructed through traces, logs, metrics, and audit.
Every failure can be located at the use-case, event, transaction, and dependency stage.
User and operator activity is auditable without leaking PII or secrets.
Every dashboard and alert is actionable.
```

---

# 2. Observability Signals

OpsMind uses four related signals:

```text
Trace
Log
Metric
Audit
```

## Trace

Answers which services and stages a request or event crossed, and where latency or failure occurred.

## Log

Answers what a specific component observed at a specific time with safe context.

## Metric

Answers whether the system is healthy at an aggregate level.

## Audit

Answers who performed which action on which resource, whether the action was allowed, and what result occurred.

Audit is not an ordinary application log and is not replaced by log retention.

---

# 3. Telemetry Ownership

Ticket Workflow emits:

- HTTP server telemetry
- Application use-case telemetry
- Domain transition telemetry
- PostgreSQL client telemetry
- Outbox Publisher telemetry
- RabbitMQ consumer telemetry
- Scheduler telemetry
- Authorization decision telemetry
- Business audit events
- Security audit references

Other domains emit their own telemetry while propagating trace context.

---

# 4. OpenTelemetry Resource

Every service instance sets:

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

Request-specific identifiers such as TicketId, WorkflowId, user ID, or email are not resource attributes.

---

# 5. Trace Context

## W3C Trace Context

HTTP propagates:

```http
traceparent
tracestate
```

RabbitMQ propagates them as message headers.

## Correlation ID

Business correlation uses:

```http
X-Correlation-Id
```

After Ticket creation, the preferred value is the display ID such as `INC-2048`.

Before a display ID exists, the service uses a generated request correlation identifier.

## Causation ID

```text
causation_id
```

identifies the command or event that triggered the current operation.

## Important Correlation Fields

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

These belong in trace attributes or structured logs, never as high-cardinality metric labels.

---

# 6. Trace Boundaries

Root or consumer spans are created for:

- HTTP requests
- RabbitMQ consumption
- Scheduler runs
- Manual recovery commands
- Outbox polling cycles
- Reconciliation jobs

Application use cases use child spans.

RabbitMQ consumers preserve asynchronous relationships through propagated context and span links.

---

# 7. Span Naming

Pattern:

```text
<domain>.<component>.<operation>
```

Examples:

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

Generic names such as `handle`, `process`, or `run` are avoided.

---

# 8. HTTP Server Spans

Recommended server span:

```text
HTTP POST /api/v1/tickets
```

Semantic attributes:

```text
http.request.method
http.route
http.response.status_code
url.scheme
server.address
server.port
```

OpsMind attributes:

```text
opsmind.use_case_id
opsmind.actor_type
opsmind.authorization_result
opsmind.ticket_status
opsmind.idempotency_replayed
opsmind.error_code
```

Request bodies, authorization headers, raw idempotency keys, email addresses, and message bodies are excluded.

---

# 9. Application Use-case Spans

Each command or query creates:

```text
ticket.usecase.<operation>
```

Examples:

```text
ticket.usecase.create_ticket
ticket.usecase.add_message
ticket.usecase.cancel_ticket
ticket.usecase.reopen_ticket
ticket.usecase.start_verification
```

Attributes:

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

```text
ticket.domain.transition
```

Attributes:

```text
opsmind.transition_id
opsmind.from_status
opsmind.to_status
opsmind.reason_code
opsmind.guard_result
opsmind.aggregate_version
```

Ticket descriptions, message text, approver names, and raw tool responses are not recorded.

---

# 11. Transaction Span

```text
ticket.transaction
```

Attributes:

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

Span events:

```text
transaction.begin
transaction.retry
transaction.commit
transaction.rollback
```

---

# 12. Database Spans

OpenTelemetry JDBC instrumentation is used.

Suggested names:

```text
SELECT ticket.tickets
UPDATE ticket.tickets
INSERT ticket.outbox_events
```

Allowed attributes:

```text
db.system
db.namespace
db.operation.name
db.collection.name
server.address
db.response.status_code
```

Production disables complete SQL and parameter capture by default.

---

# 13. Outbox Publisher Tracing

Poll span:

```text
ticket.outbox.poll
```

Attributes:

```text
opsmind.batch_size_requested
opsmind.batch_size_claimed
opsmind.publisher_instance
opsmind.oldest_pending_age_seconds
```

Claim span:

```text
ticket.outbox.claim
```

Publish span:

```text
ticket.outbox.publish
```

Attributes:

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

Message IDs are trace attributes, not metric labels.

---

# 14. RabbitMQ Consumer Tracing

Span:

```text
ticket.consumer.<normalized_event_type>
```

Examples:

```text
ticket.consumer.approval_granted
ticket.consumer.tool_execution_completed
ticket.consumer.verification_completed
```

Attributes:

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

Span events:

```text
event.schema_validated
event.duplicate_detected
event.classified_stale
event.classified_out_of_order
event.applied
event.sent_to_dlq
```

---

# 15. Span Links for Asynchronous Flow

The Outbox publish span links to the original business transaction span.

The consumer span links to the producer span through propagated message context.

When a direct parent-child relationship would create a trace lasting hours, the consumer starts a new root and adds a span link.

---

# 16. Scheduler Tracing

Root spans:

```text
ticket.scheduler.auto_close
ticket.scheduler.sla_scan
ticket.scheduler.outbox_cleanup
ticket.scheduler.integrity_scan
```

Attributes:

```text
opsmind.job_name
opsmind.job_run_id
opsmind.candidate_count
opsmind.processed_count
opsmind.success_count
opsmind.failure_count
opsmind.duration_ms
```

Per-Ticket child spans are sampled or capped for large batches.

---

# 17. Reconciliation Tracing

```text
ticket.reconciliation.open
ticket.reconciliation.investigate
ticket.reconciliation.recover
ticket.reconciliation.verify
ticket.reconciliation.resolve
```

Attributes:

```text
opsmind.reconciliation_type
opsmind.reconciliation_status
opsmind.priority
opsmind.recovery_outcome
opsmind.operator_type
opsmind.approval_required
opsmind.verification_result
```

Evidence bodies are not copied into traces.

---

# 18. LangSmith Correlation

Agent Runtime uses LangSmith.

Ticket Service does not duplicate complete Ticket content into LangSmith.

Correlation metadata:

```text
ticket_trace_id
ticket_id
workflow_id
resolution_cycle_id
agent_run_id
```

Rules:

- Ticket traces store `agent_run_id`.
- LangSmith metadata stores Ticket trace references.
- PII is redacted before capture.
- LangSmith retention is managed independently.
- Complete prompt and response capture is disabled or heavily redacted in production.

LangSmith supplements but does not replace system-level OpenTelemetry.

---

# 19. Structured Logging

All logs use JSON.

Canonical schema:

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

# 20. Log-level Policy

`TRACE` is local-only by default.

`DEBUG` records safe internal decisions.

`INFO` records business milestones such as Ticket creation, transitions, event processing, Outbox publication, and reconciliation completion.

`WARN` records recoverable failures, retries, stale spikes, confirm timeouts, denials, and ordering issues.

`ERROR` records exhausted retries, DLQ, permanent dependency failure, and integrity problems.

`FATAL` records unsafe startup failures such as migrations or invalid security configuration.

---

# 21. Log Message Rules

Messages describe facts:

```text
"Ticket transition applied"
"Outbox publish confirm timed out"
"Approval event rejected because workflow reference is stale"
```

Dynamic values remain structured fields.

Messages such as `Something went wrong` are prohibited.

---

# 22. Log Redaction

Never log:

```text
Authorization
JWT
Idempotency-Key
password
access token
refresh token
API key
private key
session cookie
Ticket description
message body
raw prompt
raw LLM response
raw tool response
```

Safe alternatives:

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

```text
Field Allowlist
→ Secret Pattern Detection
→ PII Redaction
→ Length Limit
→ Structured Encoding
```

On redaction failure:

- Drop the unsafe field.
- Increment a redaction-failure metric.
- Never fall back to the raw value.

Metric:

```text
ticket_telemetry_redaction_failure_total
```

---

# 24. Log Sampling

High-volume successful events may be sampled:

- Successful reads
- Empty Outbox polls
- Duplicate-event acknowledgments

Never sample away:

- Security alerts
- High-risk denials
- DLQ
- Integrity failures
- Manual recovery
- Compensation
- EventId payload conflicts

---

# 25. Metric Naming

Pattern:

```text
opsmind_ticket_<subject>_<unit_or_type>
```

Examples:

```text
opsmind_ticket_requests_total
opsmind_ticket_request_duration_seconds
opsmind_ticket_transitions_total
opsmind_ticket_outbox_pending
opsmind_ticket_outbox_oldest_age_seconds
```

Counters end in `_total`.

Durations use seconds.

---

# 26. Metric Cardinality

Allowed labels:

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

Forbidden labels:

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

Every label value comes from a controlled finite set.

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

Routes use templates rather than dynamic URLs.

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

Labels may include status, reason, priority, controlled application code, and result.

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

Labels:

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

---

# 35. Histogram Buckets

HTTP and use-case duration:

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

Event processing:

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

Resolution duration:

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

Buckets are revised using observed production distributions.

---

# 36. Exemplars

Prometheus histograms may attach trace exemplars.

Trace IDs are exemplars, not regular labels.

This allows navigation from a slow latency bucket to an example trace.

---

# 37. SLIs

## API Availability

```text
successful eligible requests / total eligible requests
```

Expected client validation and authorization failures are excluded.

Server failures, timeouts, and dependency unavailability are included.

## API Latency

Route-template p95 and p99 server latency.

## Event Processing Success

```text
APPLIED + DUPLICATE + STALE
/
valid consumed events
```

Retry exhaustion and DLQ count as failures.

## Outbox Freshness

Age of the oldest unpublished event.

## Transition Correctness

Successful legal transitions divided by valid attempts, excluding expected business rejection.

## Audit Completeness

```text
audited committed high-risk operations
/
all committed high-risk operations
```

Target: 100%.

---

# 38. SLOs

Recommended MVP or demo targets:

## API

```text
99.5% monthly availability
p95 read latency < 300ms
p95 command latency < 800ms
p99 command latency < 2s
```

## Events

```text
99% of valid events processed or safely classified within 60s
99.9% within 5m
```

## Outbox

```text
99% published within 10s
oldest pending event < 5m
```

## Audit

```text
100% of high-risk committed actions have audit records
```

## Resolution Pipeline

```text
99% of successful tool results enter verification within 60s
```

These are project targets and require business approval before production use.

---

# 39. Error Budget

For a 99.5% monthly availability SLO:

```text
monthly error budget ≈ 0.5%
```

Suggested policy:

- 25% consumed: warning
- 50%: pause nonessential releases
- 75%: reliability fixes only
- 100%: incident and change freeze

---

# 40. Dashboard Set

## Ticket Workflow Overview

- Request rate
- Error rate
- p95 and p99 latency
- Created, resolved, and closed Tickets
- Current status distribution
- Reopen and escalation rates

## Event and Outbox

- Event throughput
- Classification
- Duplicate, stale, and out-of-order rates
- Retry and DLQ
- Outbox backlog and age
- Publish failures and confirm latency

## Database and Transaction

- Transaction latency
- Rollback
- Deadlocks
- Optimistic conflicts
- Connection-pool saturation
- Slow queries and lock waits

## Security

- Authentication failures
- Authorization denials
- Cross-queue denials
- Sensitive reads
- Secret detection
- Wrong producers
- Recovery denials

## Reconciliation

- Open cases
- Age by priority
- Outcomes
- Manual-review backlog
- Compensation success and failure

## SLA

- Response and resolution deadlines
- Breach rate
- Time in state
- Auto-close activity

---

# 41. Golden Signals

```text
Latency
Traffic
Errors
Saturation
```

Latency includes HTTP, transaction, event, publish, and database latency.

Traffic includes requests, commands, events, and scheduler candidates.

Errors include 5xx, DLQ, retry exhaustion, integrity, and security errors.

Saturation includes database pools, queue depth, thread pools, Outbox backlog, CPU, and memory.

---

# 42. Alert Design

Every alert contains:

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

Context-free alerts such as `Error rate high` are prohibited.

---

# 43. Critical Alerts

## Outbox Stuck

```text
opsmind_ticket_outbox_oldest_age_seconds > 300
```

for five minutes.

## DLQ Growth

Any security or integrity event entering DLQ alerts immediately.

## Audit Missing

A committed high-risk operation without an audit record is critical.

## Security

- Secret detection
- Wrong producer
- EventId payload conflict
- Cross-Ticket corruption
- Unauthorized database write

---

# 44. Warning Alerts

- API latency over target
- Increasing out-of-order rate
- Increasing optimistic conflicts
- Duplicate spike
- Reconciliation age over SLA
- Rate-limit spike
- High database pool usage
- Scheduler failures

---

# 45. Alert Deduplication

Alert grouping uses low-cardinality fields:

```text
service
environment
alertname
error_category
event_type
queue
```

TicketId is not an alert group label.

---

# 46. Audit Model

Audit categories:

```text
Business Audit
Security Audit
Recovery Audit
Sensitive Read Audit
```

Business audit includes lifecycle and assignment actions.

Security audit includes denials, cross-queue access, wrong producers, and secret detection.

Recovery audit includes replay, correction, compensation, and data repair.

Sensitive-read audit includes auditor and cross-queue administrative access.

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

High-risk operations store a local audit reference in the same transaction:

```text
Update Ticket
Insert Status History
Insert Audit Record
Insert Audit Outbox Event
Commit
```

A central audit platform may consume asynchronously, but the local atomic audit reference cannot be lost.

---

# 49. Audit Immutability

Audit storage is:

- Append-only
- Protected from ordinary update and delete
- Retention-controlled
- Corrected through new records rather than mutation

Production may add WORM storage, hash chains, or signed batches.

---

# 50. Audit Hash Chain

Recommended production enhancement:

```text
record_hash =
SHA-256(
  canonical audit record
  + previous_record_hash
)
```

The MVP may reserve:

```text
previous_hash
record_hash
```

---

# 51. Audit Minimization

Audit does not store message bodies, descriptions, secrets, raw JWTs, complete events, raw IP addresses, or full user agents.

It may store actor IDs, actions, decisions, resource IDs, scopes, queue IDs, reason codes, trace IDs, and snapshot hashes.

---

# 52. Retention

Recommended:

| Signal | Retention |
|---|---:|
| Business Audit | 2 years |
| Security Audit | 2–7 years by policy |
| Recovery Audit | 2–7 years |
| Sensitive Read Audit | 2 years |
| Operational Logs | 30–90 days |
| Traces | 7–30 days |
| Metrics | 13 months |

Final policy requires organizational approval.

---

# 53. Trace Sampling

Local and CI:

```text
100%
```

Demo and staging:

```text
20–100%
```

Production head sampling:

```text
5–10%
```

Tail sampling always preserves:

- Errors
- Slow traces
- DLQ
- Security alerts
- Reconciliation
- Compensation
- High-risk administrative actions
- Unknown tool results
- Event conflicts

---

# 54. Sampling Consistency

A trace uses a consistent sampling decision.

Trace flags propagate through RabbitMQ.

Audit is recorded regardless of trace sampling.

Metrics are independent of sampling.

---

# 55. Log and Trace Retention by Environment

| Environment | Logs | Traces |
|---|---:|---:|
| local | 7 days | 3 days |
| ci | 7 days | 7 days |
| demo | 30 days | 14 days |
| staging | 30 days | 14 days |
| prod | 30–90 days | 14–30 days |

Security incidents may place data under legal hold.

---

# 56. Golden Path Trace

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

Expected spans include HTTP create, use case, transaction, database inserts, Outbox publication, workflow events, approval, tool result, verification, resolution, and auto-close.

Important references:

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

1. Confirm impact using error rate, operation, environment, event type, and time window.
2. Locate traces by trace ID, correlation ID, display ID, or EventId.
3. Validate Ticket snapshot, history, cycle, pending action, processed events, and Outbox.
4. Inspect Agent, Approval, Tool, Verification, RabbitMQ, and DLQ state.
5. Choose retry, stale classification, replay, correction, escalation, or reconciliation.
6. Verify legal state, continuous history, Outbox health, event completion, and user recovery.

---

# 58. Incident-debugging Security

Operators do not paste complete descriptions into chat, expose tokens in issues, display message bodies on dashboards, disable authorization for debugging, edit Ticket status through SQL, or enable unrestricted prompt capture in production.

---

# 59. Search Examples

Search by Ticket:

```text
ticket_id = "01J..."
```

Search by event:

```text
event_id = "evt-500"
```

Search transition failures:

```text
error_code = "INVALID_STATE_TRANSITION"
AND use_case_id = "UC-26"
```

Search publish problems:

```text
event_type = "ticket.resolved"
AND confirm_result = "TIMEOUT"
```

The design does not bind queries to one observability vendor.

---

# 60. Observability Backend

Suggested MVP stack:

```text
OpenTelemetry Collector
Prometheus
Grafana
Loki
Tempo or Jaeger
```

LangSmith remains specific to Agent Runtime and LLM traces.

Architecture:

```text
Application
→ OTel SDK
→ OTel Collector
→ Metric, Log, and Trace Backends
```

---

# 61. OpenTelemetry Collector

The Collector performs:

- Batching
- Retry
- Resource enrichment
- Redaction
- Attribute dropping
- Tail sampling
- Export

Services do not maintain multiple vendor-specific exporters directly.

---

# 62. Telemetry Failure Behavior

If telemetry backends are unavailable:

- Business requests continue.
- Bounded in-memory batching is used.
- Excess telemetry is dropped.
- Self-metrics report failures.
- Business threads are not blocked indefinitely.

Self-metrics:

```text
otel_export_failed_total
otel_dropped_spans_total
otel_dropped_logs_total
```

Audit failure is different: a high-risk business action fails closed or rolls back when its required local audit record cannot be stored.

---

# 63. Audit Delivery

Local audit records commit with business state.

The service may publish:

```text
ticket.audit_recorded.v1
```

The central audit consumer deduplicates by `auditId`.

Audit event payloads remain minimal.

---

# 64. Dashboard Access Control

Dashboard access is role-based:

- Developers: technical telemetry
- Support managers: operational and SLA data
- Security: security dashboards
- Auditors: audit dashboards
- Executives: aggregate KPIs

Sensitive panels are redacted or hidden by default.

---

# 65. Metric Integrity

Metrics are not a business source of truth.

For example, `resolved_total` does not replace resolution records in PostgreSQL.

Metric loss never changes Ticket state.

---

# 66. Observability Tests

Unit tests:

```text
shouldAddUseCaseIdToSpan
shouldNotAddTicketDescriptionToSpan
shouldRedactTokenFromLog
shouldRejectHighCardinalityMetricLabel
shouldCreateAuditRecordForHighRiskAction
shouldNotSampleOutSecurityIncidentTrace
```

Integration tests:

```text
shouldPropagateTraceparentThroughRabbitMq
shouldLinkOutboxPublishToBusinessTrace
shouldEmitDuplicateEventMetric
shouldRecordRollbackReason
shouldWriteAuditAtomicallyWithCancel
shouldExposePrometheusMetrics
shouldExportStructuredJsonLogs
```

End-to-end tests:

```text
shouldTraceGoldenPathAcrossServices
shouldLocateTicketByCorrelationId
shouldLinkAgentRunToTicketTrace
shouldAlertWhenOutboxOldestAgeExceedsThreshold
shouldPreserveAuditWhenTraceIsUnsampled
```

---

# 67. Cardinality Tests

CI validates:

- Metric-label allowlists
- Label-value sources
- No TicketId labels
- No dynamic URL IDs as labels
- Controlled application-code values
- Stable error-code enums

---

# 68. Redaction Tests

Inject:

```text
Bearer eyJ...
-----BEGIN PRIVATE KEY-----
password=secret
api_key=abc
MFA recovery code
```

Verify the values do not appear in logs, traces, metrics, LangSmith, or audit, while the secret-detection metric increases.

---

# 69. Failure-injection Tests

```text
FAIL_AFTER_TICKET_UPDATE
FAIL_OUTBOX_INSERT
FAIL_BEFORE_PUBLISH_CONFIRM
FAIL_AFTER_CONSUMER_COMMIT_BEFORE_ACK
OTEL_COLLECTOR_UNAVAILABLE
PROMETHEUS_SCRAPE_FAILURE
AUDIT_INSERT_FAILURE
```

Verify rollback visibility, duplicate classification, Outbox alerts, nonblocking telemetry failure, and fail-closed audit behavior.

---

# 70. Runbook Requirements

Every critical alert has a runbook containing:

```text
Meaning
User impact
Detection query
Dashboard
Likely causes
Safe first actions
Unsafe actions
Recovery
Verification
Escalation owner
Closure criteria
```

---

# 71. Data-model Increment

A later `07-data-model` revision should add:

```text
ticket.security_audit_records
ticket.business_audit_records
ticket.sensitive_read_audit
```

or a unified:

```text
ticket.audit_records
```

Important fields:

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

# 72. Event-contract Increment

Recommended events:

```text
ticket.audit_recorded.v1
ticket.security_alert_raised.v1
ticket.reconciliation_opened.v1
ticket.reconciliation_resolved.v1
```

Audit events never contain message bodies or secrets.

---

# 73. Acceptance Criteria

- [x] Trace, log, metric, and audit responsibilities defined
- [x] OpenTelemetry resources and trace context defined
- [x] HTTP, use-case, domain, transaction, database, Outbox, consumer, scheduler, and reconciliation spans defined
- [x] Asynchronous span links defined
- [x] LangSmith correlation and redaction defined
- [x] Structured logging schema and levels defined
- [x] Log and trace redaction defined
- [x] Metric naming and cardinality defined
- [x] API, domain, transaction, event, Outbox, scheduler, security, and reconciliation metrics defined
- [x] SLIs, SLOs, and error budget defined
- [x] Dashboards and alerts defined
- [x] Audit model, atomicity, immutability, retention, and hash chain defined
- [x] Sampling, retention, and telemetry-failure behavior defined
- [x] Golden Path tracing and incident debugging defined
- [x] Observability, cardinality, redaction, and failure-injection tests defined

---

# 74. Next Step

Create:

```text
13-package-and-class-design/README_CN.md
13-package-and-class-design/README_EN.md
```

That document will map the first twelve design documents into the Java and Spring Boot package structure, classes, interfaces, controllers, application services, domain model, repositories, event consumers, Outbox Publisher, security, and observability code.
