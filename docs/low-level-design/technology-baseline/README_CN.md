# OpsMind 技术基线 1.1

> **项目：** OpsMind — Adaptive Multi-Agent Platform for Enterprise IT Support  
> **阶段：** Low-Level Design  
> **状态：** Accepted for MVP  
> **本次更新：** 正式加入 LangSmith Agent Observability 与 Evaluation  
> **建议位置：** `docs/low-level-design/technology-baseline/README_CN.md`

---

## 1. 技术基线目标

本文档冻结 OpsMind MVP 的共同技术前提，供后续 `Ticket Workflow`、`Agent Runtime`、`Tool Gateway`、`Policy & Approval`、`Memory`、`Observability` 和 `Evaluation` 详细设计使用。

```text
Java / Spring Boot
= 确定性业务、事务、状态机、安全与工具执行

Python / FastAPI
= Agent、LLM、RAG、Memory 与 Evaluation

OpenTelemetry
= 完整分布式系统的工程可观测性

LangSmith
= Agent、Prompt、Tool、RAG、Memory 与 Evaluation 可观测性
```

---

## 2. 技术决策总表

| 领域 | MVP 选择 | 状态 |
|---|---|---|
| Frontend | React 19 + TypeScript + Vite 8.x | Frozen |
| Frontend Runtime | Node.js 24 LTS + pnpm | Frozen |
| Core Backend | Java 21 + Spring Boot 3.5.x | Frozen |
| Agent Runtime | Python 3.13.x + FastAPI + Pydantic | Frozen |
| Agent Orchestration | LangGraph，放在内部抽象后 | Provisional |
| Main Database | PostgreSQL 18.x | Frozen |
| Vector Search | pgvector | Frozen |
| Cache / Lease | Redis | Frozen |
| Message Broker | RabbitMQ | Frozen |
| Object Storage | S3-Compatible；本地 MinIO | Frozen |
| Authentication | Keycloak + OIDC / OAuth 2.0 | Frozen |
| Authorization | RBAC + 有限 ABAC | Frozen |
| API | REST + OpenAPI 3.1 | Frozen |
| Events | RabbitMQ + Versioned JSON Envelope | Frozen |
| Realtime UI | Server-Sent Events | Frozen |
| System Observability | OpenTelemetry + Prometheus + Grafana + Loki | Frozen |
| Trace Backend | Tempo 或 Jaeger | Provisional |
| Agent Observability | LangSmith | Frozen for MVP |
| Offline Evaluation | LangSmith Dataset + Experiment | Frozen for MVP |
| Online Evaluation | LangSmith Online Evaluator | Optional |
| Local Deployment | Docker Compose | Frozen |
| Future Deployment | Kubernetes | Deferred |
| Repository | Monorepo | Frozen |
| CI | GitHub Actions | Frozen |

具体 Patch Version 固定在 Lockfile、Container Image 与 Build Configuration 中。

---

## 3. 运行时与服务边界

```text
React / TypeScript
        │ REST + SSE
        ▼
Java / Spring Boot
        ├── api-gateway
        ├── ticket-workflow-service
        ├── tool-policy-gateway
        └── mock-enterprise-services
        │
        ├── REST
        └── RabbitMQ Events
                 │
                 ▼
Python / FastAPI
        ├── agent-runtime-service
        ├── agent-worker
        ├── memory processing
        └── evaluation
```

### Java 负责

- Ticket 生命周期、状态机与事务边界
- Transactional Outbox
- Policy、Approval、RBAC 与 ABAC
- Tool Gateway 与企业 Connector
- 幂等、Audit 和业务 API

### Python 负责

- Agent Workflow 与多 Agent 协作
- LLM 调用与结构化输出
- Context、RAG、Memory Processing
- Evaluation 与 Improvement Candidate

### 强制边界

- Python 不能直接修改 Java 拥有的数据表。
- Java Domain 不依赖 Python 具体实现。
- 跨服务协作使用 REST 或 Versioned Event。
- Agent 不能直接访问企业管理员 API 或 Credential。

---

## 4. 前端基线

```text
TypeScript
React 19
Vite 8.x
Node.js 24 LTS
pnpm
React Router
TanStack Query
Zustand
React Hook Form
Zod
Tailwind CSS
shadcn/ui
Vitest
React Testing Library
Playwright
SSE
```

MVP 使用一个 Web Portal，通过 `employee`、`support`、`admin`、`manager`、`auditor` 角色路由划分页面。

规则：

- 不直接访问 PostgreSQL、Redis 或 RabbitMQ。
- 所有业务状态通过 API 获取。
- 权限必须由后端再次校验。
- 不展示 Secret、Credential 或未脱敏的内部 Prompt。
- SSE 支持 Reconnect、Heartbeat 与 Last-Event-ID。

---

## 5. Java 核心后端基线

```text
Java 21
Spring Boot 3.5.x
Spring Web MVC
Spring Security
Spring Data JPA
JdbcTemplate
Jakarta Validation
Flyway
Spring AMQP
Resilience4j
springdoc-openapi
JUnit 5
Mockito
Testcontainers
ArchUnit
OpenTelemetry Java
```

包结构：

```text
api
application
domain
infrastructure
config
```

规则：

- Controller 不包含业务规则。
- API 不直接暴露 JPA Entity。
- Aggregate 使用 Optimistic Lock。
- 业务数据与 Outbox Event 同事务写入。
- 数据库事务内不调用 RabbitMQ、LLM 或远程 API。
- 使用 UTC 与 `Instant`。
- Integration Test 优先使用 Testcontainers。

---

## 6. Python Agent Runtime 基线

```text
Python 3.13.x
FastAPI
Pydantic
SQLAlchemy 2.x
Alembic
uv
pytest
httpx
LangGraph
LangSmith SDK
OpenTelemetry Python
RabbitMQ Async Client
```

内部接口：

```text
WorkflowStore
CheckpointStore
AgentRegistry
ModelGateway
ToolClient
PolicyClient
MemoryClient
EvaluationHook
AgentTraceClient
```

规则：

- 每个 Agent Output 必须通过 Pydantic Schema。
- LLM 自由文本不能直接成为 Tool Command。
- Workflow 与 Checkpoint 持久化到 PostgreSQL。
- Redis 不能成为 Workflow 唯一状态源。
- 每个 Workflow 配置迭代、Tool、时间、成本和 No-progress 限制。
- LangGraph 与 LangSmith 通过内部接口隔离。

---

## 7. 数据与基础设施

Schema Ownership：

```text
ticket.*      → Ticket Workflow
agent.*       → Agent Runtime
memory.*      → Memory & Knowledge
tool.*        → Tool Gateway
policy.*      → Policy & Approval
evaluation.*  → Evaluation
audit.*       → Audit
```

- 服务只写自己拥有的 Schema。
- Java Schema 使用 Flyway，Python Schema 使用 Alembic。
- Audit Append-only，Mutable Aggregate 包含 Version。
- Redis 用于 Cache、Rate Limit、Task Lease 与 SSE Metadata，不作为 Durable State。
- RabbitMQ 使用 At-least-once Delivery + Idempotent Consumer。
- MinIO 通过 S3-Compatible Interface 保存附件、截图、知识文档与 Export。

---

## 8. API 与 Event 标准

REST：

```text
HTTPS
REST
OpenAPI 3.1
JSON
ISO 8601 UTC
/api/v1
/internal/v1
```

Event Envelope：

```json
{
  "eventId": "evt-1001",
  "eventType": "approval.granted",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-23T16:30:00Z",
  "producer": "tool-policy-gateway",
  "traceId": "otel-trace-abc",
  "correlationId": "INC-2048",
  "ticketId": "INC-2048",
  "workflowId": "wf-7788",
  "aggregateId": "approval-81",
  "aggregateVersion": 2,
  "payload": {}
}
```

Event 使用小写点号命名；Breaking Change 升级 Major Version；Contract 使用 JSON Schema；不得携带 Credential；PII 最小化。

---

## 9. Authentication、Authorization 与 Secret

```text
Keycloak
OpenID Connect
OAuth 2.0
JWT
RBAC + 有限 ABAC
```

角色：`EMPLOYEE`、`IT_SUPPORT`、`IT_ADMIN`、`SECURITY_ADMIN`、`IT_MANAGER`、`AUDITOR`。

ABAC 条件包括 Ticket Ownership、Assigned Queue、Target System Permission、Approval Expiration、Ticket Cancellation 与 Separation of Duties。

Secret 不提交到 Git，不进入 Prompt，不允许 Agent 读取，并从 Log 与 Trace 中脱敏。

---

## 10. 双层可观测性

### System Observability

```text
OpenTelemetry
Prometheus
Grafana
Loki
Tempo / Jaeger
```

负责跨服务 Trace、HTTP/RabbitMQ/Database Span、API Latency、Queue Lag、Error Rate、Log 与基础设施指标。

### Agent Observability

```text
LangSmith
```

负责 Agent Run Tree、LLM Input/Output、Prompt Version、Tool Call、RAG Retrieval、Memory Candidate、Agent Handoff、Token、Latency、Feedback、Dataset、Experiment 与 Evaluation。

LangSmith 不作为 Ticket、Checkpoint、Approval、Policy、Tool Execution、Audit 或 Long-term Memory 的权威来源。

---

## 11. OpenTelemetry 与 LangSmith 关联

统一字段：

```text
trace_id
correlation_id
ticket_id
workflow_id
agent_task_id
agent_name
agent_version
prompt_version
model_name
tool_execution_id
approval_id
environment
```

MVP 决策：

```text
OpenTelemetry SDK = System Span
LangSmith SDK = Agent Semantic Trace
```

未来可评估 OpenTelemetry Collector Fan-out 到 Tempo 与 LangSmith。

---

## 12. LangSmith Project 与 Trace 规范

Projects：

```text
opsmind-local-agent
opsmind-ci-evaluation
opsmind-demo-agent
opsmind-staging-agent
```

Trace Tree：

```text
opsmind-workflow
├── triage-agent
├── identity-agent
│   ├── get-account-status
│   ├── check-group-membership
│   └── get-duo-enrollment
├── knowledge-agent
├── resolution-agent
└── verification-agent
```

Metadata 至少包含 `ticket_id`、`workflow_id`、`agent_task_id`、`agent_version`、`prompt_version`、`otel_trace_id` 与 `environment`。

---

## 13. LangSmith 数据安全

发送前必须执行：

```text
PII Redaction
Secret Redaction
Credential Removal
Document Filtering
Log Truncation
Prompt Sanitization
```

禁止发送 Password、Access Token、Refresh Token、API Key、Session Cookie、Private Key、Authentication Header、企业 Secret 或完整未脱敏 Login Log。

数据分类：`PUBLIC`、`INTERNAL`、`SENSITIVE`、`SECRET`。`SECRET` 永远不能发送到 LangSmith。

---

## 14. Sampling 与成本控制

| 场景 | 采样率 |
|---|---:|
| Local Development | 100% |
| Golden Path Demo | 100% |
| Offline Evaluation | 100% |
| Unit Test | 0% |
| Selected Integration Test | 100% |
| Normal CI Build | 0% |
| Demo Environment | 25%–100% |
| Health Check | 0% |

优先保留 Agent Error、Policy Violation Candidate、Invalid Tool Arguments、Verification Failure、Human Escalation、No-progress Loop、High Cost 与 Memory Conflict。

---

## 15. LangSmith Evaluation

MVP Dataset：

```text
identity-mfa-golden-dataset
```

Deterministic Evaluator：

- Classification Accuracy
- Root Cause Match
- Tool Allowlist
- Tool Argument Schema
- Required Approval
- Forbidden Action
- Final Ticket State

LLM-as-Judge 只用于 Explanation Quality、Evidence Grounding、Handoff Completeness 与 User Instruction Clarity。关键安全规则不能只依赖 LLM Judge。

Release Gate 要求 Policy Violation 与 Forbidden Tool Call 为 0，Root Cause、Tool Argument 与 Verification 不得回归，Critical Case 全部通过，Cost 不超过阈值。

---

## 16. LangSmith 故障降级

```text
Fail-open for telemetry
Fail-closed for security
```

Trace Export 失败不能阻止 Ticket Workflow，也不能跳过 Policy、Approval、Credential 或 Tool Validation。Retry 必须有上限且不阻塞；失败写入本地 Log 和 Metric；Audit Record 不能丢失。

---

## 17. Deployment、Testing 与 CI

Docker Compose 启动 Web、Java/Python 服务、PostgreSQL、Redis、RabbitMQ、Keycloak、MinIO、OTel Collector、Prometheus、Grafana、Loki 与 Tempo/Jaeger。LangSmith 使用外部 Workspace，不是本地强制 Container。

测试：

```text
Frontend: Vitest + React Testing Library + Playwright
Java: JUnit 5 + Mockito + Testcontainers + ArchUnit
Python: pytest + httpx + LangSmith Evaluation
```

GitHub Actions 执行 Formatting、Linting、Static Analysis、Unit、Contract、Integration、Agent Evaluation Gate、Build、Container Build、Dependency Scan 与 Secret Scan。

---

## 18. 明确不采用与暂缓事项

MVP 不采用：

- LangSmith 替代 OpenTelemetry
- OpenTelemetry 替代 LangSmith Evaluation
- LangSmith 作为 Business Audit 或 Workflow Store
- 每个 Agent 一个微服务
- Kafka
- 独立 Vector Database
- Redis Durable State
- Kubernetes First
- Event Sourcing
- Full CQRS

暂缓：

- Cloud Provider
- Production Secret Manager
- Tempo / Jaeger 最终选择
- Kubernetes 工具
- LangGraph 最终决定
- LangSmith Online Evaluation
- LangSmith Self-hosted / Hybrid
- LLM / Embedding / Reranker
- Kafka
- Temporal
- 物理 Database-per-service

---

## 19. 下一步与文件夹结构

```text
docs/
└── low-level-design/
    ├── README_CN.md
    ├── README_EN.md
    ├── domains/
    │   ├── 01-user-access-authentication/
    │   ├── 02-ticket-workflow/
    │   ├── 03-agent-runtime-orchestration/
    │   ├── 04-memory-knowledge/
    │   ├── 05-tool-integration-gateway/
    │   ├── 06-policy-approval-governance/
    │   ├── 07-evaluation-improvement/
    │   └── 08-observability-platform/
    ├── api/
    ├── events/
    ├── data-model/
    ├── diagrams/
    └── technology-baseline/
        ├── README_CN.md
        └── README_EN.md
```

Ticket Workflow 深挖顺序：Domain Model → Invariants → State Machine → Use Cases → API/Event Contract → Data Model → Transaction/Outbox → Concurrency/Idempotency → Failure → Observability → Class Design → Testing。

---

## 20. 官方参考

- [LangSmith OpenTelemetry Tracing](https://docs.langchain.com/langsmith/trace-with-opentelemetry)
- [LangSmith Evaluation](https://docs.langchain.com/langsmith/evaluation)
- [Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/)
- [React 19](https://react.dev/blog/2024/12/05/react-19)
- [Vite Releases](https://vite.dev/releases)
- [Python 3.13](https://docs.python.org/3.13/)
- [PostgreSQL 18](https://www.postgresql.org/docs/18/)
- [OpenTelemetry](https://opentelemetry.io/docs/)
