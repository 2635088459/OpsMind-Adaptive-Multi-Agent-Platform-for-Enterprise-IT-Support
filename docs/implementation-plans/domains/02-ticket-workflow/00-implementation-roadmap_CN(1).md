# OpsMind Ticket Workflow — Implementation Roadmap

> **文档编号：** IMP-TW-000  
> **领域：** `02-ticket-workflow`  
> **文档类型：** Implementation Roadmap  
> **版本：** 1.1  
> **状态：** Reviewed Draft  
> **实现方式：** Spec-Driven Development + Test-Driven Development + Vertical Slice Delivery  
> **设计基线：** `docs/low-level-design/domains/02-ticket-workflow/`  
> **代码目录：** `services/ticket-workflow-service/`  
> **Feature Spec 目录：** `docs/specs/domains/02-ticket-workflow/`  
> **Traceability 目录：** `docs/traceability/domains/02-ticket-workflow/`

---

# 1. 文档目的

本文档定义 `02-ticket-workflow` 从已批准的 Low-Level Design 进入代码实现阶段时的：

- 实现阶段与顺序
- 排序原因
- 每个阶段的目标、范围和非目标
- 每个阶段对应的设计文档
- 每个阶段需要编写的 Feature Spec
- SDD + TDD 执行方式
- 阶段交付物和退出条件
- 跨阶段质量门禁
- 设计、Spec、测试和代码之间的追踪关系

本文档不重新设计 Ticket Workflow，也不复制现有 14 份 LLD。

它回答的是：

```text
已经知道系统应该怎样设计之后，
应该按照什么顺序把它实现出来，
为什么按这个顺序，
每个阶段做到什么程度才允许进入下一阶段。
```

---


# 2. Review 结论与修订决定

本次 Review 对 Phase 划分、目录、技术依赖和 Phase 00 Scope 作出以下决定：

## 2.1 Phase 划分

保留 Phase 00–10 的总体顺序。

原因：

- 顺序与 Ticket 生命周期和代码依赖方向一致。
- 每个业务阶段由多个小型 Feature Spec 组成，而不是一个大型需求。
- Phase 是交付里程碑；Feature Spec 才是最小 Review 和 TDD 单元。
- Phase 03、05、06、07 可以先通过 Contract Stub 验证，不必等待其他 Domain 的真实服务全部完成。

## 2.2 目录

统一使用：

```text
docs/implementation-plans/domains/02-ticket-workflow/
docs/specs/domains/02-ticket-workflow/
docs/traceability/domains/02-ticket-workflow/
services/ticket-workflow-service/
```

`traceability` 增加 `domains/` 层级，与 `implementation-plans` 和 `specs` 保持一致。

## 2.3 技术基线

Ticket Workflow 实现线冻结为：

```text
Java 21
Spring Boot 3.5.16
Maven 3.9.16 Wrapper
PostgreSQL 18.4
RabbitMQ 4.3.4
Keycloak 26.7.0
Testcontainers 2.0.5
```

Spring Boot 4.1.0 不在本实现线中静默采用。若以后升级 Major Version，必须先更新 Technology Baseline 并新增 ADR。

## 2.4 Phase 00 Scope

Phase 00 只建立可信的构建、测试、配置、安全和基础设施连接环境。

Phase 00 不创建：

- Ticket Aggregate
- Ticket 业务表
- Ticket API
- Ticket Integration Event
- Transactional Outbox 业务实现

## 2.5 横切能力

Security、Audit、Idempotency、Transaction 和 Observability 不能全部推迟到 Phase 09。

每个 Vertical Slice 都必须实现当前功能所需的最小横切能力；Phase 09 只负责集中强化和补齐。

---

# 3. 当前设计基线

`02-ticket-workflow` 已完成以下 LLD：

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

这些文档共同构成实现阶段的设计 Source of Truth。

实现过程中不得：

- 绕过 Business Invariant。
- 增加未定义的 Generic Status Mutation。
- 为了演示破坏 Transactional Outbox。
- 让 Event Consumer 跳过 Idempotency。
- 让 Tool Success 直接把 Ticket 设为 `RESOLVED`。
- 让 Controller、Scheduler 或 Reconciliation 直接修改 JPA Entity 状态。
- 只修改代码而不更新受影响的 LLD、Spec 和测试。

---

# 4. 为什么还需要 Implementation Roadmap

不同文档回答不同问题：

| 层次 | 回答的问题 |
|---|---|
| LLD | 系统应该是什么样 |
| Implementation Roadmap | 先实现什么、后实现什么、为什么 |
| Feature Spec | 某一个功能必须怎样工作 |
| Test | 怎样用机器验证功能满足要求 |
| Code | 怎样让测试通过 |

没有 Roadmap，容易出现：

- 一次性创建大量类，但没有功能可运行。
- 先实现 Consumer，却没有稳定 Aggregate。
- 先接入 Agent Runtime，却没有 `ticket.created` 和 Workflow 生命周期。
- 先写 Controller，再补状态机，导致 API 和 Domain 不一致。
- 一次实现全部状态转换，调试范围过大。
- 代码可以启动，但无法解释每个提交对应哪个 Use Case。

---

# 5. 实现方法

本领域采用：

```text
LLD Baseline
→ Implementation Phase
→ Feature Spec
→ Failing Tests
→ Minimum Implementation
→ Refactor
→ Integration Verification
→ Traceability Update
```

即：

```text
Spec-Driven Development
+
Test-Driven Development
+
Vertical Slice Delivery
```

---

# 6. Spec-Driven Development

每个业务切片在编码前必须有小而明确的 Feature Spec。

Feature Spec 不复制完整 LLD，而是引用：

- Use Case ID
- API ID
- State Transition ID
- Business Invariant ID
- Event Contract
- Data Table
- Security Scope
- Audit Requirement
- Observability Requirement
- Testing Requirement

示例：

```text
SPEC-TW-001 Create Ticket

References:
- UC-01
- API-001
- SM-001
- BI-001
- BI-008
- ticket.created.v1
- ticket.tickets
- ticket.ticket_status_history
- ticket.outbox_events
```

Feature Spec 只补充当前实现切片需要的：

- Scope
- Non-goals
- Preconditions
- Detailed Behavior
- Transaction Boundary
- Acceptance Scenarios
- Error Scenarios
- Tests First
- Definition of Done

---

# 7. Test-Driven Development

每个 Feature Spec 按以下循环实现：

```text
RED
→ GREEN
→ REFACTOR
→ VERIFY
```

## RED

先编写失败测试：

- Domain Unit Test
- Application Test
- API 或 Event Contract Test
- 必要的 Integration Test

测试失败原因必须是功能尚未实现，而不是环境损坏。

## GREEN

编写满足当前 Spec 的最少代码。

这里的“最少代码”表示：

```text
只实现当前 Feature Spec 需要的行为，
但必须遵守全部已批准的架构和业务约束。
```

## REFACTOR

在测试保护下：

- 改善命名
- 拆分类
- 消除重复
- 强化类型
- 保持 Package Dependency
- 保持 API 和 Event Contract 稳定

## VERIFY

运行：

- Unit
- Application
- Integration
- Contract
- Security
- Architecture
- Observability checks

更新 Traceability Matrix 后才可完成该 Spec。

---

# 8. 为什么采用 Vertical Slice

每个阶段交付一个从入口运行到持久化或事件出口的完整切片。

示例：

```text
POST /api/v1/tickets
→ Authentication
→ Authorization
→ CreateTicketApplicationService
→ Ticket.create()
→ PostgreSQL
→ Status History
→ Audit
→ Outbox ticket.created
→ HTTP Response
```

该切片同时覆盖：

- API
- Application
- Domain
- Persistence
- Transaction
- Outbox
- Security
- Audit
- Observability
- Test

它优于先横向写完所有 Controller、所有 Repository、所有 Entity，再尝试连接起来。

优势：

- 每个阶段都有可演示结果。
- 更早发现设计冲突。
- 每个 Pull Request 目标清晰。
- 每个阶段都可独立回归。
- 更容易展示系统演进。
- 降低一次性生成大量未验证代码的风险。

---


# 9. 跨领域 Contract-first Integration Policy

Ticket Workflow 的部分 Phase 依赖其他 Domain 的事件和行为：

```text
Agent Runtime
Policy and Approval
Tool Integration Gateway
Verification
Notification
```

这些 Domain 尚未实现时，不阻塞 Ticket Workflow 的 Vertical Slice。

采用：

```text
Approved Event Contract
→ Golden JSON Fixture
→ Deterministic Stub Producer / Consumer
→ Ticket Workflow Integration Test
→ Later Real Service Compatibility Test
```

规则：

- Stub 必须严格使用 `06-event-contracts` 的 Envelope 和 Schema。
- Stub 不能调用 Ticket Service 的内部数据库。
- Stub 结果必须确定性，可通过 Scenario ID 控制。
- 真实服务接入后，运行同一组 Contract Test。
- Contract 不匹配时修复 Producer 或更新经过批准的 Contract，不能在 Consumer 中静默兼容任意 Payload。

---

# 10. 实现阶段总览

```text
Phase 00  Engineering Foundation
Phase 01  Create Ticket Vertical Slice
Phase 02  Ticket Query and Message Slice
Phase 03  Triage and Investigation Slice
Phase 04  Waiting for User Slice
Phase 05  Policy and Approval Slice
Phase 06  Tool Execution Slice
Phase 07  Verification and Resolution Slice
Phase 08  Close, Reopen, Cancel, Assign and Escalate Slice
Phase 09  Security, Audit and Operational Hardening
Phase 10  Reconciliation, Chaos and Release Readiness
```

这些阶段仍然属于：

```text
02-ticket-workflow
```

实际代码统一位于：

```text
services/ticket-workflow-service/
```

---


# 11. 每个 Vertical Slice 的最低横切基线

从 Phase 01 开始，每一个 Feature Spec 至少检查：

```text
Authentication / Service Identity
Authorization
Input or Event Contract Validation
Business Invariants
Transaction Boundary
Idempotency when applicable
History
Audit when required
Outbox when an event is emitted
Structured Error
Trace and Metrics
Unit / Integration / Contract Tests
```

Phase 09 不负责补救前面完全没有安全和审计的业务代码。

Phase 09 只做：

- 完整 Keycloak Realm / Client 集成
- Queue Authorization 强化
- Step-up Authentication
- Sensitive Read Audit
- Secret Detection 强化
- Dashboard / Alert / Rate Limit
- 安全和可观测性回归检查

---

# 12. Phase 00 — Engineering Foundation

## 目标

建立能够支持 SDD + TDD 的工程和测试基础，但不实现 Ticket 业务。

## 为什么最先做

没有以下能力，就无法可靠执行 TDD：

- Java / Spring Boot 工程
- Maven Wrapper
- JUnit
- Testcontainers
- PostgreSQL
- RabbitMQ
- ArchUnit
- CI
- 配置管理
- Health Check

Phase 00 是设计落地环境，不是业务功能。

## 主要设计引用

```text
13-package-and-class-design
14-testing-strategy
12-observability-and-audit
11-security-and-authorization
technology-baseline
```

## 主要交付物

- `services/ticket-workflow-service/`
- Java 21 + Spring Boot
- Maven Wrapper
- Package Skeleton
- Base Configuration
- PostgreSQL / RabbitMQ Testcontainers
- ArchUnit
- CI Fast Verify
- Health / Readiness
- Initial README

## 退出条件

```text
./mvnw clean verify
```

通过，并且：

- Spring Context 可以启动。
- PostgreSQL 和 RabbitMQ Testcontainers 可以启动。
- ArchUnit 规则可以执行。
- CI 可以执行。
- 尚未出现 Ticket 业务代码。

详细计划见：

```text
phase-00-engineering-foundation_CN.md
```

---

# 13. Phase 01 — Create Ticket Vertical Slice

## 目标

交付第一个完整业务切片：

```text
Authenticated Employee
→ Create Ticket
→ NEW
→ Status History
→ Audit
→ Outbox ticket.created
→ Response
```

## 为什么此时做

Ticket 创建是整个 Workflow 的入口。

它第一次验证：

- Ticket Aggregate
- `SM-001`
- UC-01
- API-001
- PostgreSQL Persistence
- Transactional Outbox
- API Idempotency
- Resource Ownership
- Audit
- Trace / Metrics

它还为 Agent Runtime 提供后续所需的：

```text
ticket.created.v1
```

## Feature Spec

```text
SPEC-TW-001-create-ticket
```

## 退出条件

- 同一个 Idempotency Key 不重复创建 Ticket。
- Ticket、History、Audit、Outbox 原子提交。
- `ticket.created.v1` Contract 通过。
- 创建后状态只能是 `NEW`。
- Phase 01 全部测试通过。

---

# 14. Phase 02 — Ticket Query and Message Slice

## 目标

让 Employee 和 Support 可以安全读取 Ticket，并添加 Message。

## 范围

```text
Get Ticket
List My Tickets
List Support Queue
Get Timeline
Add Requester Message
Add Internal Support Message
```

## 为什么此时做

后续 Agent、Support 和用户交互都依赖稳定的查询与消息能力。

该阶段验证：

- Read Model
- Cursor Pagination
- Field Visibility
- Resource Ownership
- Queue Authorization
- Append-only Message
- Timeline Composition

## Feature Specs

```text
SPEC-TW-002-get-ticket
SPEC-TW-003-list-requester-tickets
SPEC-TW-004-add-ticket-message
SPEC-TW-005-support-queue-query
SPEC-TW-006-ticket-timeline
```

## 退出条件

- Employee 不能读取其他用户 Ticket。
- Internal Message 不对 Employee 可见。
- Cursor Pagination 稳定。
- Message Append-only。
- Query 不加载完整 Aggregate。

---

# 15. Phase 03 — Triage and Investigation Slice

## 目标

实现：

```text
NEW
→ TRIAGING
→ INVESTIGATING
```

并关联 Agent Workflow。

## 为什么此时做

完成创建、查询和消息能力后，系统才具备进入自动化工作流的稳定基础。

该阶段首次验证：

- Service-to-Service Event
- Active Workflow
- Agent Runtime references
- Classification result
- Event deduplication
- Stale workflow handling

## Feature Specs

```text
SPEC-TW-007-start-triage
SPEC-TW-008-complete-classification
SPEC-TW-009-agent-workflow-failure
```

## 退出条件

- 一个 Ticket 只能有一个 Active Workflow。
- Classification Event 必须匹配 Ticket 和 Workflow。
- Duplicate Event 无重复业务效果。
- Old Workflow Event 被分类为 Stale。

---

# 16. Phase 04 — Waiting for User Slice

## 目标

实现：

```text
TRIAGING / INVESTIGATING
→ WAITING_FOR_USER
→ TRIAGING / INVESTIGATING
```

## 为什么在 Approval 前实现

真实 IT 调查经常需要用户补充信息。如果先做 Approval 和 Tool，系统会缺少最常见的分支流程。

## Feature Specs

```text
SPEC-TW-012-request-user-input
SPEC-TW-013-user-reply-and-resume
```

## 关键要求

- 一个 Ticket 只能有一个 Open User Input Request。
- User Reply 必须关联当前请求。
- `WAITING_FOR_USER` 暂停 SLA。
- Reply 不能恢复旧 Workflow。
- Message 与状态变化原子提交。

---

# 17. Phase 05 — Policy and Approval Slice

## 目标

实现：

```text
INVESTIGATING
→ WAITING_FOR_APPROVAL
→ EXECUTING
```

以及：

```text
REJECTED / EXPIRED
→ INVESTIGATING
```

## 为什么在 Tool Execution 前实现

Tool Gateway 不应拥有自行决定高风险操作的权限。Approval 是 Tool Execution 的安全前置条件。

## Feature Specs

```text
SPEC-TW-014-request-approval
SPEC-TW-015-apply-approval-granted
SPEC-TW-016-apply-approval-rejected
SPEC-TW-017-apply-approval-expired
SPEC-TW-018-apply-auto-approved-policy
```

## 关键要求

- Approval 绑定 Ticket、Workflow、Action 和 Risk Context。
- Expired Approval 不可执行。
- Approval 不可复用。
- Wrong Producer 进入 DLQ。
- Duplicate Approval 幂等。

---

# 18. Phase 06 — Tool Execution Slice

## 目标

实现：

```text
EXECUTING
→ VERIFYING
```

以及已知安全失败、未知结果和内部失败分支。

## Feature Specs

```text
SPEC-TW-019-tool-execution-completed
SPEC-TW-020-tool-execution-failed
SPEC-TW-021-tool-result-unknown
```

## 关键要求

- Tool Success 不能直接 Resolve。
- Tool Execution 必须匹配 Pending Action。
- Unknown Result 不能盲目重试。
- Unknown Side Effect 需要 Verification 或 Escalation。
- 同一个 ToolExecutionId 不能重复产生业务效果。

---

# 19. Phase 07 — Verification and Resolution Slice

## 目标

实现：

```text
VERIFYING
→ RESOLVED
```

以及验证失败的重试和升级路径。

## Feature Specs

```text
SPEC-TW-022-start-verification
SPEC-TW-023-verification-success
SPEC-TW-024-verification-failure
SPEC-TW-025-resolve-ticket
```

## 关键要求

- Proposal 不是 Verification。
- 只有当前 Workflow / Cycle / Attempt 的可信结果可以 Resolve。
- 第三次失败或 Unsafe 结果进入 `ESCALATED`。
- `RESOLVED` 不等于 `CLOSED`。
- Resolution Cycle 完整保存。

---

# 20. Phase 08 — Lifecycle Completion Slice

## 目标

完成主要人工和终态操作：

```text
Close
Auto-close
Reopen
Cancel
Assign
Escalate
Resume from Escalation
Retry from Failed
```

## Feature Specs

```text
SPEC-TW-026-confirm-resolution
SPEC-TW-027-auto-close
SPEC-TW-028-reopen-ticket
SPEC-TW-029-cancel-ticket
SPEC-TW-030-assign-ticket
SPEC-TW-031-escalate-ticket
SPEC-TW-032-resume-escalated-ticket
```

## 关键要求

- Closed 在规定窗口内可 Reopen。
- Reopen 创建新 Workflow / Resolution Cycle / SLA Cycle。
- Cancelled 在 MVP 不可 Reopen。
- EXECUTING / VERIFYING 不能被不安全取消。
- Assignment 受 Queue Authorization 约束。

---

# 21. Phase 09 — Security, Audit and Operational Hardening

## 目标

把前面逐步接入的安全和可观测能力提升到设计要求。

## 范围

- Keycloak Realm / Client integration
- Role and Scope hardening
- Support Queue authorization
- Field Visibility
- Secret Detection
- Step-up Authentication
- Sensitive Read Audit
- OpenTelemetry
- Metrics
- Dashboard
- Alert
- Rate Limit

## 为什么不是最后才开始安全

基础 Authentication、Authorization、Audit 和 Telemetry 必须在前面每个切片同步实现。

Phase 09 是集中补齐和强化，不是第一次加入安全。

## Feature Specs

```text
SPEC-TW-031-support-queue-authorization
SPEC-TW-032-sensitive-read-audit
SPEC-TW-033-secret-detection
SPEC-TW-034-step-up-authentication
```

---

# 22. Phase 10 — Reconciliation, Chaos and Release Readiness

## 目标

验证系统在重复、乱序、崩溃、未知结果和跨服务冲突下仍能安全恢复。

## 范围

- Reconciliation Case
- DLQ Triage
- Event Replay
- Correction Event
- Compensation
- Integrity Scan
- Crash Window
- Chaos Tests
- Performance Tests
- Release Gate

## 为什么最后做

Reconciliation 依赖前面已经存在的状态机、事件、Outbox、幂等、Tool、Verification、Audit 和 Security。

Unknown Result 和基本 Reconciliation Hook 必须从 Phase 06 开始预留。

## Feature Specs

```text
SPEC-TW-035-open-reconciliation-case
SPEC-TW-036-replay-event
SPEC-TW-037-correction-event
SPEC-TW-038-compensation
SPEC-TW-039-data-integrity-repair
```

---

# 23. 每个 Phase 的统一结构

每一份 Phase Plan 必须包含：

```text
1. Objective
2. Why This Phase Now
3. Design References
4. Included Feature Specs
5. Scope
6. Non-goals
7. Architecture Decisions Applied
8. TDD Execution Order
9. Implementation Tasks
10. Test Plan
11. Deliverables
12. Risks
13. Exit Criteria
14. Traceability Update
```

---

# 24. 每个 Feature Spec 的统一结构

```text
1. Spec Identity
2. Objective
3. Design References
4. Actor
5. Scope
6. Non-goals
7. Preconditions
8. Command / Input
9. Detailed Behavior
10. State Transition
11. Business Invariants
12. Transaction Boundary
13. Events
14. Security
15. Audit
16. Observability
17. Error Scenarios
18. Acceptance Scenarios
19. Tests First
20. Definition of Done
```

---

# 25. Traceability

维护：

```text
docs/traceability/domains/02-ticket-workflow/traceability-matrix.yaml
```

示例：

```yaml
SPEC-TW-001:
  phase: Phase-01

  design:
    use_cases:
      - UC-01
    api:
      - API-001
    transitions:
      - SM-001
    invariants:
      - BI-001
      - BI-008
    events:
      - ticket.created.v1

  implementation:
    classes:
      - Ticket
      - CreateTicketApplicationService
      - PublicTicketController
      - TicketPersistenceAdapter

  tests:
    - TicketCreationTest
    - CreateTicketApplicationServiceTest
    - CreateTicketControllerTest
    - CreateTicketAtomicityIT
```

---

# 26. Pull Request Strategy

一个 Feature Spec 建议对应一个或少数几个小型 PR。

推荐提交顺序：

```text
docs(spec): define SPEC-TW-001 create ticket
test(ticket): add failing create ticket domain tests
feat(ticket): implement ticket creation domain behavior
feat(persistence): add create ticket migrations and adapter
feat(api): implement create ticket endpoint
test(integration): verify ticket creation transaction and outbox
docs(traceability): link SPEC-TW-001 to code and tests
```

禁止一次提交：

```text
Implement all Ticket Workflow
```

---

# 27. 设计变更规则

实现中发现设计问题时：

## 小型澄清

例如字段命名或测试辅助结构：

- 更新 Feature Spec。
- 必要时更新 LLD。
- 在 PR 中说明。

## 架构或业务语义变更

例如：

- 新状态
- 新状态转换
- 修改 Approval 信任边界
- 改变事件语义
- 改变事务边界
- 改变安全模型

必须：

```text
ADR or LLD Change
→ Review
→ Feature Spec Update
→ Test Update
→ Code Change
```

禁止仅修改代码。

---

# 28. 跨阶段质量门禁

每个 Phase 必须满足：

- 对应 Spec 已 Review。
- 新测试先于或与实现同时提交。
- Critical Invariant 有覆盖。
- API / Event Contract 无未批准 Breaking Change。
- Domain 无 Spring / JPA 依赖。
- PostgreSQL Integration Test 使用真实 PostgreSQL。
- Outbox 与业务事务保持原子。
- 无 Secret 泄漏到 Log / Trace / Event。
- ArchUnit 通过。
- Traceability 已更新。
- README 和 Run Command 已更新。

---

# 29. MVP 与完整设计的关系

建议 MVP 截止点：

```text
Phase 00
→ Phase 01
→ Phase 02
→ Phase 03
→ Phase 04
→ Phase 05
→ Phase 06
→ Phase 07
→ Phase 08 的核心路径
```

Portfolio Demo 至少展示：

```text
Create
→ Triage
→ Approval
→ Tool
→ Verification
→ Resolve
```

Phase 09 和 Phase 10 可根据时间分为：

```text
MVP Required
Portfolio Hardening
Production-oriented Extension
```

但设计中声明的安全边界不能因为 MVP 而被绕过。

---

# 30. Roadmap 完成定义

当以下条件满足时，Ticket Workflow Implementation Roadmap 执行完成：

- 所有计划 Phase 已有状态。
- 每个已实现 Use Case 有 Feature Spec。
- 每个 Spec 可追踪到 LLD。
- 每个 Spec 可追踪到测试和代码。
- Golden Path E2E 通过。
- 关键 Failure Path 通过。
- Release Gate 通过。
- README 可以指导新开发者启动和验证服务。

---

# 31. 当前下一步

```text
1. Review 本 Roadmap
2. Review phase-00-engineering-foundation_CN.md
3. 批准目录和技术基线
4. 创建 Phase 00 工程骨架
5. 完成 Phase 00 Exit Criteria
6. 编写 SPEC-TW-001-create-ticket_CN.md
7. 进入 Phase 01 的 RED 阶段
```
