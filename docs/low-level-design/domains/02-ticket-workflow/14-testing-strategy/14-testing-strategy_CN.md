# OpsMind Ticket Workflow — 14 Testing Strategy

> **领域：** Ticket & Business Workflow  
> **文档类型：** Low-Level Testing Strategy and Quality Gate  
> **版本：** 1.0  
> **状态：** Proposed for Implementation  
> **依赖：** `01-domain-model_CN.md` 至 `13-package-and-class-design_CN.md`  
> **测试技术：** JUnit 5、AssertJ、Mockito、Spring Boot Test、MockMvc、Testcontainers、WireMock、ArchUnit、Awaitility、Pact、k6 或 Gatling  
> **目标平台：** Java 21、Spring Boot、PostgreSQL、RabbitMQ、Keycloak、OpenTelemetry  
> **建议路径：** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/14-testing-strategy_CN.md`

---

# 1. 文档目的

本文档定义 Ticket Workflow 的测试层次、测试边界、质量门禁、CI 执行顺序和发布准入标准。

核心目标：

```text
任何业务不变量都必须有可执行测试。
任何状态转换都必须同时测试允许路径和拒绝路径。
任何跨服务事件都必须测试 Schema、重复、迟到、乱序和冲突。
任何数据库和消息可靠性保证都必须在真实 PostgreSQL 与 RabbitMQ 上验证。
任何高风险操作都必须测试授权、审批、审计和验证。
任何发布都不能只依赖手工点击。
```

---

# 2. 测试原则

## 2.1 测试行为，不测试实现细节

测试应验证：

```text
给定业务状态
执行业务动作
得到合法结果
```

避免过度验证私有方法调用次数、内部集合顺序和无业务意义的 Getter。

## 2.2 测试越靠近 Domain 越快越多

复杂业务规则优先放入 Domain Unit Test。数据库、RabbitMQ、Keycloak 和 OpenTelemetry 行为由 Integration Test 验证。

## 2.3 不 Mock 关键基础设施语义

以下能力不能只依赖 Mock：

- PostgreSQL Constraint
- Optimistic Lock
- `FOR UPDATE SKIP LOCKED`
- RabbitMQ Redelivery
- Publisher Confirm
- DLQ
- JSONB
- Flyway
- Keycloak Claim
- Trace Context Propagation

## 2.4 每个 Bug 必须增加 Regression Test

Bug 修复 PR 必须包含能复现原问题的测试，并标记影响的 Invariant、Transition 或 Use Case。

## 2.5 测试失败必须可诊断

失败输出至少包含：

- Expected / Actual
- Ticket Status
- Aggregate Version
- EventId / CommandId
- 数据库记录摘要
- Container Log Reference

---

# 3. Test Pyramid

建议比例：

```text
Domain Unit Test              40%
Application Unit / Slice      20%
Persistence / Messaging       20%
API / Security Contract       10%
End-to-End / Chaos / Load     10%
```

避免大量昂贵 E2E，却缺少 Domain Test。

---

# 4. 测试层次

```text
L0 Static Analysis
L1 Domain Unit
L2 Application Unit
L3 Component / Slice
L4 Infrastructure Integration
L5 Contract
L6 End-to-End
L7 Chaos / Performance / Security
```

## L0 Static Analysis

- 编译
- Checkstyle
- SpotBugs / Error Prone
- Dependency Scan
- Secret Scan
- ArchUnit
- JSON Schema Lint
- OpenAPI Lint

## L1 Domain Unit

不启动 Spring，不访问数据库和 Broker。

## L2 Application Unit

Mock Outbound Port，验证 Use Case 编排。

## L3 Component / Slice

使用 `@WebMvcTest`、`@DataJpaTest`、Security Slice 和 Mapper Test。

## L4 Infrastructure Integration

使用真实 PostgreSQL、RabbitMQ 和 Keycloak Testcontainer。

## L5 Contract

验证 API 和 Event Contract 的稳定性。

## L6 End-to-End

运行完整 Ticket Workflow 和必要 Stub Service。

## L7 Non-functional

Chaos、Load、Soak、Dynamic Security。

---

# 5. 测试命名

格式：

```text
should<ExpectedBehavior>When<Condition>
```

示例：

```text
shouldMoveToVerifyingWhenToolExecutionSucceeds
shouldRejectResolutionWithoutTrustedVerification
shouldReturnStoredResponseWhenIdempotencyKeyIsReplayed
shouldAckOldWorkflowEventAsStale
```

推荐使用 `@Nested` 按业务动作分组。

---

# 6. Given / When / Then

```java
// Given
Ticket ticket = TicketFixtures.investigatingTicket();

// When
ticket.cancel(reason, actor, now, policy);

// Then
assertThat(ticket.status()).isEqualTo(CANCELLED);
```

Setup、Execution 和 Assertion 应清晰分离。

---

# 7. Business Invariant Test

`02-business-invariants` 中每个 Critical 和 High Invariant 必须至少有一个自动化测试。

推荐在测试名中包含 Invariant ID：

```text
BI032_shouldAllowOnlyOneActiveWorkflow
BI048_shouldRejectApprovalForDifferentAction
BI067_shouldRequireCurrentVerificationBeforeResolve
```

## 7.1 Invariant Coverage Matrix

维护：

```text
src/test/resources/invariant-coverage.yaml
```

示例：

```yaml
BI-032:
  - TicketWorkflowInvariantTest#shouldAllowOnlyOneActiveWorkflow
BI-048:
  - ApprovalReferenceInvariantTest#shouldRejectApprovalForDifferentAction
```

CI 报告：

```text
Invariant ID
Test Class
Test Method
Result
```

## 7.2 必须覆盖的关键 Invariant

- 一个 Active Workflow
- 一个 Active Pending Action
- Tool Success 不直接 Resolve
- Verification 独立于 Proposal
- Resolve 必须匹配当前 Cycle / Workflow / Attempt
- `RESOLVED != CLOSED`
- Reopen 创建新 Workflow / Resolution Cycle / SLA Cycle
- Unknown Tool Result 不盲目重试
- Ticket、History、Outbox 原子提交
- Processed Event 与业务更新原子提交
- EventId 相同但 Payload 不同进入 DLQ
- Terminal Ticket 拒绝旧周期事件
- Optimistic Conflict 必须 Reload + Re-evaluate

---

# 8. State Machine Test

`SM-001` 至 `SM-034` 每条转换必须有：

```text
1 个 Happy Path
1 个 Guard Failure
1 个 Side Effect Assertion
```

每条 Transition 验证：

- Source Status
- Trigger
- Required Reference
- Target Status
- Version +1
- Status History
- Domain Event
- Outbox Event Type
- SLA Effect
- Pending Action Effect

## 8.1 Illegal Transition

使用参数化测试覆盖所有稳定状态和非法动作。

## 8.2 Terminal State

验证：

- 普通 Late Event 不推进 `CLOSED` / `CANCELLED`
- Closed 在窗口内可 Reopen
- Cancelled 在 MVP 不可 Reopen
- Stale / Audit Record 仍可写入

## 8.3 Verification Failure Counter

```text
Failure 1 → INVESTIGATING
Failure 2 → INVESTIGATING
Failure 3 → ESCALATED
Unsafe Result → ESCALATED immediately
```

---

# 9. Domain Unit Test

Package：

```text
src/test/java/.../ticket/domain
```

测试类：

```text
TicketCreationTest
TicketTransitionTest
TicketCancellationTest
TicketReopenTest
TicketResolutionTest
TicketAssignmentTest
TicketEscalationTest
TicketVerificationPolicyTest
TicketPendingActionPolicyTest
TicketSlaPolicyTest
ValueObjectValidationTest
```

Domain Test 不使用：

```text
@SpringBootTest
@DataJpaTest
PostgreSQL
RabbitMQ
```

Value Object 覆盖 Null、Blank、长度、格式、Equality 和 Boundary。

Domain Event 验证 Event Type、TicketId、状态、Version、OccurredAt、Actor，并验证不包含 Secret 或 Raw Body。

---

# 10. Application Unit Test

Package：

```text
src/test/java/.../ticket/application
```

Mock：

- Repository Port
- Authorization Port
- Idempotency Port
- Audit Port
- Outbox Port
- Clock
- ID Generator

不 Mock Ticket Aggregate。

测试类：

```text
CreateTicketApplicationServiceTest
AddTicketMessageApplicationServiceTest
CancelTicketApplicationServiceTest
ReopenTicketApplicationServiceTest
ApprovalEventApplicationServiceTest
ToolEventApplicationServiceTest
VerificationEventApplicationServiceTest
AssignmentApplicationServiceTest
EscalationApplicationServiceTest
```

验证：

- Authorization 在 Domain Behavior 前执行
- Idempotency Replay 不重复保存
- Expected Version 传递
- History / Audit / Outbox 写入
- Error Mapping
- Reconciliation Trigger
- Result DTO

只验证关键边界交互，不对所有内部调用进行脆弱的 `verify(...)`。

---

# 11. API Controller Test

使用：

```text
@WebMvcTest
MockMvc
Spring Security Test
```

测试：

- Route
- Request Validation
- Scope
- Mapping
- Response
- Error Envelope
- `If-Match`
- `Idempotency-Key`
- Cursor Pagination
- Content Type

Validation 覆盖：

- Missing Field
- Blank Title
- Description Too Long
- Invalid UUID
- Invalid Enum
- Unknown Field
- Payload Too Large

Error Response 不得泄漏 Stack Trace。

---

# 12. API Contract Test

验证：

- OperationId 唯一
- Request Schema
- Response Schema
- Error Schema
- Security Requirement
- Status Code
- Header
- Pagination

未经版本升级禁止：

- 删除字段
- 修改字段类型
- Optional 改 Required
- 删除 Status Code
- 修改 Stable Enum
- 改变 Error Code 语义

CI 对 main branch OpenAPI 和当前 OpenAPI 执行 Breaking Change Diff。

---

# 13. Event Contract Test

Package：

```text
src/test/java/.../ticket/messaging/contract
```

每个 Published / Consumed Event 测试：

- Envelope
- Schema
- Version
- Routing Key
- Producer
- Required Reference
- Data Classification
- Secret-free
- Unknown Field Policy

Golden Fixture：

```text
src/test/resources/contracts/events/
```

示例：

```text
approval-granted-v1.valid.json
approval-granted-v1.invalid-missing-action.json
ticket-resolved-v1.valid.json
```

Published Event Test：

```text
Domain Event → Integration Event → JSON
```

Consumed Event Test：

```text
Fixture JSON → DTO → Application Command
```

---

# 14. Consumer Contract Test

每个 Consumer 验证：

- Allowed Producer
- Wrong Producer
- Schema Failure
- Duplicate
- Business Duplicate
- Stale
- Out-of-order
- Corrupt Reference
- Terminal Result Conflict
- ACK / Retry / DLQ

测试类：

```text
ApprovalEventConsumerContractTest
ToolExecutionEventConsumerContractTest
VerificationEventConsumerContractTest
```

---

# 15. PostgreSQL Integration Test

使用：

```text
PostgreSQL Testcontainer
Flyway
真实 Schema
```

禁止使用 H2 替代关键数据库测试。

核心：

```text
TicketPersistenceAdapterIT
TicketHistoryPersistenceIT
OutboxPersistenceIT
ProcessedEventPersistenceIT
IdempotencyPersistenceIT
TicketQueryRepositoryIT
FlywayMigrationIT
```

验证：

- PK / FK
- Unique
- Check Constraint
- Partial Unique Index
- Version
- JSONB
- Cursor Pagination
- Timeline Order
- Role Visibility

Partial Unique 必须测试：

```text
one active workflow
one active pending action
one open user request
one active SLA cycle
one cycle number per Ticket
```

---

# 16. Flyway Test

每个 Migration Test：

1. 空数据库迁移到最新。
2. 从前一稳定版本升级。
3. 验证 Constraint。
4. 验证 Reference Data。
5. 验证 `hibernate.ddl-auto=validate`。
6. 验证 Roll-forward Recovery Plan。

---

# 17. Optimistic Lock Test

真实 PostgreSQL 场景：

```text
Transaction A load version 3
Transaction B load version 3
A commits version 4
B fails
B reloads and re-evaluates
```

测试：

```text
shouldAllowOnlyOneConcurrentAssignment
shouldRejectBlindRetryAfterStateChanges
shouldReturnIdempotentSuccessWhenEquivalentResultAlreadyApplied
```

---

# 18. Transaction Atomicity Test

通过 Failure Injection 验证以下原子关系：

```text
Ticket
History
Audit
Outbox
Processed Event
Idempotency Response
```

测试：

```text
shouldRollbackTicketWhenHistoryInsertFails
shouldRollbackTicketWhenOutboxInsertFails
shouldRollbackProcessedEventWhenTicketUpdateFails
shouldRollbackHighRiskActionWhenAuditInsertFails
```

---

# 19. RabbitMQ Integration Test

使用：

```text
RabbitMQ Testcontainer
真实 Exchange
真实 Queue
真实 DLQ
Publisher Confirm
```

测试：

```text
RabbitMqTopologyIT
OutboxPublisherIT
ApprovalConsumerIT
ToolExecutionConsumerIT
VerificationConsumerIT
RetryQueueIT
DlqIT
TracePropagationIT
```

验证：

- Durable Exchange / Queue
- Binding
- Routing Key
- Retry TTL
- DLX
- Single Active Consumer
- Publisher Confirm
- Redelivery
- Commit-before-ACK

---

# 20. Outbox Publisher Test

测试：

- Claim Batch
- `FOR UPDATE SKIP LOCKED`
- 多 Publisher 不重复 Claim
- Lock Timeout Recovery
- Broker ACK / NACK
- Confirm Timeout
- Unroutable Message
- Retry Backoff
- Retry Exhaustion
- Duplicate Publication

核心：

```text
shouldClaimEachRowByOnlyOnePublisher
shouldNotHoldDatabaseLockWhileWaitingForConfirm
shouldMarkPublishedOnlyAfterAck
shouldRepublishSameEventIdAfterPublisherCrash
```

---

# 21. API Idempotency Test

覆盖：

```text
same key + same payload + completed → replay
same key + different payload → 409
same key + fresh in-progress → 409
stale in-progress + committed resource → rebuild response
stale in-progress + no resource → retryable
```

并发：

```text
100 concurrent identical Create requests
→ exactly one Ticket
```

---

# 22. Event Idempotency Test

覆盖：

```text
same EventId + same Hash → ACK duplicate
same EventId + different Hash → DLQ
different EventId + same approvalId → business duplicate
old workflow → stale
missing predecessor → retry
```

---

# 23. Security Test

## Authentication

```text
expired token
wrong issuer
wrong audience
unknown kid
missing scope
user token on internal API
service token on employee API
```

## Authorization

```text
employee reads own Ticket
employee denied another Ticket
support reads authorized queue
support denied unauthorized queue
auditor is read-only
admin cannot bypass state machine
```

## Field Visibility

- Employee 不看到 Internal Note
- Agent 不读取 Recovery Audit
- Tool Gateway 不接收 Description
- Auditor 得到 Redacted Body

## Step-up / Separation of Duties

- Recovery 需要 MFA
- Operator 不能批准自己的高风险 Recovery
- Correction Event 需要 Approval
- Compensation 需要新 Approval

---

# 24. Keycloak Integration Test

使用 Keycloak Testcontainer 或真实 Decoder Integration。

至少验证：

- Realm Import
- Client
- Role
- Scope
- Audience Mapper
- Group Claim
- Service Account
- Token Expiration

---

# 25. Secret and Redaction Test

注入：

```text
Bearer eyJ...
password=secret
api_key=abc
-----BEGIN PRIVATE KEY-----
MFA recovery code
```

验证不出现在：

- API Response
- Log
- Trace
- Metric
- Audit Metadata
- Event
- LangSmith

同时 `secret_detected` Metric 增加。

---

# 26. Concurrency Test

使用：

- `ExecutorService`
- `CountDownLatch`
- `CyclicBarrier`
- Awaitility
- PostgreSQL Testcontainer

覆盖：

- Duplicate Create
- Two Assignments
- Cancel vs Approval
- Approval Granted vs Expired
- Tool Success vs Failure
- Tool Success vs Unknown
- Verification Success vs Failure
- Reopen vs Auto-close
- Confirm vs Auto-close
- Multiple User Replies
- Multiple Auto-close Workers
- Multiple Outbox Publishers

最终断言：

- 唯一合法状态
- Version 连续
- 无重复 History
- 无重复 Outbox Business Event
- 无 Lost Update
- 冲突操作返回稳定 Error

---

# 27. Race Condition Matrix

维护：

```text
src/test/resources/race-condition-matrix.yaml
```

示例：

```yaml
cancel_vs_approval:
  winner_a: CANCELLED
  winner_b: EXECUTING
  forbidden:
    - CANCELLED_AND_EXECUTING
    - DUPLICATE_TOOL_ACTION
```

---

# 28. Scheduler Test

测试：

- Auto-close Due / Not Due
- Reopen Race
- Multiple Workers
- SLA Pause on WAITING_FOR_USER
- SLA Active on WAITING_FOR_APPROVAL
- Cleanup Batch
- Job Failure Isolation

Scheduler 不直接修改 JPA Entity 的规则由 ArchUnit 验证。

---

# 29. Reconciliation Test

覆盖：

- Tool Result Unknown
- Tool Terminal Conflict
- Verification Conflict
- Approval Conflict
- Long Out-of-order
- Stale Idempotency
- Data Integrity Mismatch
- Replay
- Correction Event
- Manual Recovery
- Compensation

断言：

- Unsafe Automation Freeze
- Evidence Immutable
- Recovery 通过正常 Use Case
- Recovery Audit 写入
- Original Event 不修改
- Correction 使用新 EventId
- Compensation 使用新 ActionId / ToolExecutionId

---

# 30. Audit Test

测试：

- High-risk Action 与 Audit 同事务
- Append-only
- Sensitive Read Audit
- Recovery Audit
- Before / After Hash
- Actor / Client / Scope
- TraceId / CommandId
- Audit Outbox

核心：

```text
shouldRollbackCancelWhenAuditInsertFails
shouldPreventUpdateOfAuditRecord
shouldAppendCorrectionAuditInsteadOfMutatingOriginal
```

---

# 31. Observability Test

## Trace

验证 W3C Propagation、HTTP → Outbox → RabbitMQ → Consumer、Span Name、Attribute、Error Status 和 Span Link。

## Log

验证 JSON、TraceId、CorrelationId、ErrorCode 和 Redaction。

## Metric

验证 Counter、Histogram、Gauge、Label Allowlist 和无高基数 Label。

## Audit

验证 Trace 未采样时 Audit 仍存在。

---

# 32. Architecture Test

使用 ArchUnit 验证：

```text
domain does not depend on Spring
domain does not depend on JPA
application does not depend on infrastructure implementation
controller does not access repository
scheduler does not access JPA repository directly
event contract DTO does not enter domain
JPA entity is not returned by API
```

---

# 33. End-to-End Golden Path

环境：

```text
Ticket Workflow
PostgreSQL
RabbitMQ
Keycloak
Stub Agent Runtime
Stub Approval Service
Stub Tool Gateway
Stub Verification Service
```

路径：

```text
Create
→ Triage
→ Classify
→ Investigate
→ Request Approval
→ Grant
→ Execute
→ Verify
→ Resolve
→ Close
```

断言：

- 每个 Status
- 每个 History
- 每个 Event
- Version
- Audit
- Trace
- SLA
- 最终 Resolution

---

# 34. E2E Alternative Paths

至少覆盖：

```text
WAITING_FOR_USER → User Reply
Approval Rejected
Approval Expired
Tool Known Failure
Tool Result Unknown
Verification Failure then Retry
Third Verification Failure Escalates
Requester Cancel
Reopen Resolved
Reopen Closed within Window
Reopen after Window rejected
Human Fix → Verification → Resolve
```

---

# 35. Stub Service

Stub 必须支持确定性场景：

```text
APPROVE
REJECT
EXPIRE
TOOL_SUCCESS
TOOL_SAFE_FAILURE
TOOL_UNKNOWN
VERIFY_SUCCESS
VERIFY_FAILURE
VERIFY_CONFLICT
```

通过 Scenario ID 或 Test Control API 选择，禁止随机结果造成 Flaky。

---

# 36. Chaos Test

场景：

```text
Broker Down
PostgreSQL Restart
Publisher Crash after Publish
Consumer Crash after Commit
Network Delay
Confirm Timeout
Duplicate Event
Out-of-order Event
Keycloak Unavailable
OTel Collector Unavailable
Disk / Pool Pressure
```

验证：

- 无部分事务
- 无事件丢失
- Duplicate 不重复业务效果
- Unknown Side Effect 不盲目重试
- Telemetry Failure 不阻塞普通业务
- Audit Failure 阻止高风险操作

---

# 37. Performance Test

工具：

```text
k6
或
Gatling
```

场景：

- Create Ticket
- List My Tickets
- Get Ticket
- Add Message
- Event Processing
- Outbox Publish
- Support Queue Query

MVP 目标：

```text
Read p95 < 300ms
Command p95 < 800ms
Command p99 < 2s
Event Processing p95 < 500ms
Outbox 99% < 10s
```

---

# 38. Load Model

建议：

```text
70% read existing
20% create / message
10% state-changing command
```

单独保留 Hot Ticket 场景测试 Lock Conflict。

---

# 39. Soak Test

持续：

```text
2–8 hours for demo
24 hours for staging
```

观察 Memory、Thread、DB Pool、Queue、Outbox、Log Volume、Trace Export、Scheduler Drift 和 Retry Accumulation。

---

# 40. Dynamic Security Test

至少包含：

- IDOR
- Scope Bypass
- JWT Audience Confusion
- Stored XSS
- SQL Injection
- Log Injection
- Prompt Injection
- Approval Replay
- Event Producer Spoofing
- Attachment Malware
- Recovery Replay Abuse

---

# 41. Test Data Strategy

Fixture：

```text
TicketFixtures
TicketBuilder
EventFixtures
PrincipalFixtures
ApprovalFixtures
ToolExecutionFixtures
VerificationFixtures
```

使用：

```text
Deterministic UUID
Fixed Clock
Stable EventId
Stable CommandId
```

每个 Integration Test 使用 Transaction Rollback、独立 Schema、Cleanup SQL 或 Fresh Container。

---

# 42. Fixture Naming

使用业务状态名：

```text
investigatingTicket()
waitingForApprovalTicket()
executingTicket()
verifyingTicketWithAttempt(2)
resolvedTicket()
closedTicketWithinReopenWindow()
```

避免 `ticket1()`、`dummyTicket()`。

---

# 43. Clock Strategy

所有时间逻辑使用 Injected Clock。

覆盖：

- Approval Expiry
- Reopen Window
- Auto-close 72h
- SLA Pause / Resume
- Outbox Retry
- Lock Timeout
- Idempotency Stale Threshold

禁止使用 `Thread.sleep()` 测业务时间。

---

# 44. Property-based Test

可以使用：

```text
jqwik
QuickTheories
```

适合：

- Value Object
- State Transition Sequence
- Canonical JSON Hash
- Cursor Pagination
- Event Ordering

随机测试必须输出失败 Seed。

---

# 45. Property-based State Machine

生成合法和非法 Action Sequence。

持续验证：

```text
Terminal state does not advance illegally
Version never decreases
One active workflow
Resolve always has verification
Reopen changes cycle
```

---

# 46. Mutation Testing

使用：

```text
PIT
```

重点：

```text
ticket.domain
ticket.application
```

建议：

```text
Domain Mutation Score >= 80%
Application Mutation Score >= 70%
```

---

# 47. Code Coverage

建议门禁：

```text
Overall Line >= 80%
Overall Branch >= 70%
Domain Line >= 90%
Domain Branch >= 85%
Application Line >= 85%
Application Branch >= 75%
```

禁止为了覆盖率写无断言测试。

---

# 48. Contract Coverage

必须 100% 覆盖：

- Public API Operation
- Internal API Operation
- Published Event Type
- Consumed Event Type
- Stable Error Code
- Stable Enum Value

---

# 49. Test Tag

```text
unit
application
component
integration
contract
security
concurrency
e2e
chaos
performance
```

---

# 50. CI Pipeline

## Stage 1：Fast Verify

```text
compile
format
static analysis
secret scan
unit
ArchUnit
JSON / OpenAPI lint
```

目标小于 5 分钟。

## Stage 2：Component

```text
application
web slice
security slice
mapper
```

## Stage 3：Infrastructure Integration

```text
PostgreSQL
RabbitMQ
Flyway
Keycloak
Outbox
```

## Stage 4：Contract

```text
OpenAPI diff
Event Schema
Consumer Contract
Producer Contract
```

## Stage 5：Concurrency / E2E

```text
Race Tests
Golden Path
Alternative Paths
```

## Stage 6：Security

```text
SAST
SCA
Container Scan
ZAP Baseline
```

## Stage 7：Nightly

```text
Chaos
Load
Soak
Mutation
Full E2E
```

---

# 51. Pull Request Gate

PR 必须通过：

- Compile
- Unit / Application
- ArchUnit
- Component
- Contract
- PostgreSQL Integration
- RabbitMQ Integration
- Core Security
- Coverage
- Secret / Dependency Scan

核心 Race Test 必须在 PR 执行。

---

# 52. Main Branch Gate

合并后：

- Build Image
- Container Scan
- Deploy Ephemeral Environment
- Golden Path E2E
- Migration Test
- Smoke Test
- Publish Report

---

# 53. Release Gate

Release Candidate 必须满足：

```text
0 Critical / High unresolved security finding
0 failing contract test
0 flaky critical test
100% critical invariant coverage
100% transition coverage
Golden Path success
Rollback / Outbox / Duplicate chaos success
Performance target met
Audit completeness = 100%
```

---

# 54. Test Report

CI Artifact：

```text
JUnit XML
Coverage HTML
Mutation Report
Contract Diff
Invariant Coverage
State Transition Coverage
Security Scan
Container Scan
Load Summary
Chaos Summary
```

---

# 55. Flaky Test Policy

- 不允许用无限重试掩盖。
- 必须指定 Owner。
- 24–48 小时内修复或隔离。
- Critical Path Test 不得长期 Quarantine。
- 保存失败 Seed 和 Container Log。
- 优先使用 Fixed Clock / Awaitility。

---

# 56. Test Retry Policy

CI 只允许对 Infrastructure Startup Failure 重试一次。

允许：

```text
Container pull timeout
Ephemeral port failure
CI network transient
```

不允许：

```text
Wrong state
Duplicate row
Missing event
Authorization bypass
```

---

# 57. Test Environment

## Local

Docker Compose：

```text
PostgreSQL
RabbitMQ
Keycloak
OTel Collector
```

## CI

Testcontainers。

## Demo / Staging

完整部署用于 E2E、Load 和 Chaos。

---

# 58. Testcontainers Policy

CI 不共享可变容器状态。

Local 可启用 Reuse，但测试仍需独立。

固定 Image Version，禁止依赖 `latest`。

---

# 59. Production Smoke Test

发布后仅执行低风险检查：

- Health
- Readiness
- DB Connectivity
- RabbitMQ Connectivity
- Token Validation
- Synthetic Ticket
- Outbox Publish
- Consumer Process
- Cleanup

不得对真实用户执行 Tool Action。

---

# 60. Synthetic Monitoring

周期性执行安全 Synthetic Flow：

```text
Create synthetic Ticket
→ classify
→ safe no-op action
→ verify
→ close
```

Synthetic Ticket 使用独立 Requester 和 Queue，不进入真实 KPI。

---

# 61. Bug Regression Template

每个 Bug PR 记录：

```text
Bug ID
Root Cause
Failing Scenario
Regression Test
Affected Invariant / Transition / Use Case
```

---

# 62. Test Ownership

| Area | Owner |
|---|---|
| Domain / Application | Feature Developer |
| API Contract | Backend + Frontend |
| Event Contract | Producer + Consumer |
| Security | Backend + Security Reviewer |
| Chaos / Reliability | Backend / Platform |
| Load | Backend / Platform |
| E2E | Feature Team |
| Audit | Backend + Security / Compliance |

---

# 63. Definition of Done

一个 Use Case 完成必须包含：

```text
Domain Test
Application Test
Authorization Test
API or Event Contract Test
Persistence / Messaging Integration Test
Error Path Test
Idempotency Test if applicable
Concurrency Test if applicable
Audit Test if high-risk
Trace / Metric Test
Documentation Update
```

---

# 64. MVP Minimum Test Set

## Domain

- Create
- Triage
- Waiting User
- Waiting Approval
- Execute
- Verify
- Resolve
- Close
- Cancel
- Reopen
- Escalate

## Application

- Create Ticket
- Add Message
- Approval Granted
- Tool Success
- Verification Success
- Cancel
- Reopen

## Infrastructure

- PostgreSQL Flyway
- Optimistic Lock
- Transaction + Outbox
- RabbitMQ Publish / Consume
- Processed Event Duplicate

## Security

- Own Ticket
- Other User Denied
- Support Queue
- Wrong Service Token
- Wrong Event Producer

## E2E

- Golden Path
- User Reply
- Approval Rejected
- Tool Unknown
- Verification Failure
- Reopen
- Cancel

---

# 65. Rejected Approaches

- 只有 `@SpringBootTest`
- 全部 Mock
- H2 替代 PostgreSQL
- 只测 Happy Path
- `Thread.sleep()` 测时间逻辑
- 依赖测试执行顺序
- 重试隐藏 Flaky
- 只看覆盖率

---

# 66. Acceptance Criteria

- [x] Test Pyramid 和测试层次已定义。
- [x] Business Invariant Coverage 已定义。
- [x] State Machine Transition Coverage 已定义。
- [x] Domain 与 Application Test 已定义。
- [x] API Controller 和 OpenAPI Contract Test 已定义。
- [x] Event Contract 和 Consumer Contract Test 已定义。
- [x] PostgreSQL、Flyway 和 Constraint Test 已定义。
- [x] RabbitMQ、Outbox、Redelivery、Confirm 和 DLQ Test 已定义。
- [x] API / Event Idempotency Test 已定义。
- [x] Security、Keycloak、Field Visibility 和 Redaction Test 已定义。
- [x] Concurrency 与 Race Matrix 已定义。
- [x] Scheduler、Reconciliation、Audit 和 Observability Test 已定义。
- [x] Golden Path、Alternative Path、Chaos 和 Performance Test 已定义。
- [x] Test Data、Clock、Property-based 和 Mutation Test 已定义。
- [x] Coverage Gate、Contract Coverage 和 Test Tag 已定义。
- [x] CI、PR、Main、Release Gate 已定义。
- [x] Flaky Test、Smoke Test、Synthetic Monitoring 和 Ownership 已定义。
- [x] MVP Minimum Test Set 已定义。

---

# 67. Ticket Workflow LLD 完成状态

已完成：

```text
01-domain-model
02-business-invariants
03-state-machine
04-use-cases
05-api-contracts
06-event-contracts
07-data-model
08-transaction-and-outbox
09-concurrency-and-idempotency
10-error-handling-and-reconciliation
11-security-and-authorization
12-observability-and-audit
13-package-and-class-design
14-testing-strategy
```

下一阶段：

```text
Implementation Planning
+
Spring Boot Project Skeleton
+
Flyway Migration
+
Domain Model Coding
```
