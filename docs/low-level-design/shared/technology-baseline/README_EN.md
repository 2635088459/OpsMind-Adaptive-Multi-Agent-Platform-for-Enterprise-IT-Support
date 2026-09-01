# OpsMind Technology Baseline 1.1

> **Project:** OpsMind — Adaptive Multi-Agent Platform for Enterprise IT Support  
> **Phase:** Low-Level Design  
> **Status:** Accepted for MVP  
> **Major Update:** LangSmith Agent Observability and Evaluation are now included  
> **Recommended Path:** `docs/low-level-design/shared/technology-baseline/README_EN.md`

---

## 1. Purpose

This document freezes the MVP technology baseline shared by Ticket Workflow, Agent Runtime, Tool Gateway, Policy and Approval, Memory, Observability, and Evaluation.

```text
Java / Spring Boot
= deterministic business logic, transactions, state machines, security, and execution

Python / FastAPI
= agents, LLMs, RAG, memory, and evaluation

OpenTelemetry
= engineering observability across the distributed system

LangSmith
= observability for agents, prompts, tools, RAG, memory, and evaluation
```

---

## 2. Decision Summary

| Area | MVP Choice | Status |
|---|---|---|
| Frontend | React 19 + TypeScript + Vite 8.x | Frozen |
| Frontend Runtime | Node.js 24 LTS + pnpm | Frozen |
| Core Backend | Java 21 + Spring Boot 3.5.x | Frozen |
| Agent Runtime | Python 3.13.x + FastAPI + Pydantic | Frozen |
| Agent Orchestration | LangGraph behind internal abstractions | Provisional |
| Main Database | PostgreSQL 18.x | Frozen |
| Vector Search | pgvector | Frozen |
| Cache / Lease | Redis | Frozen |
| Message Broker | RabbitMQ | Frozen |
| Object Storage | S3-compatible; MinIO locally | Frozen |
| Authentication | Keycloak + OIDC / OAuth 2.0 | Frozen |
| Authorization | RBAC + limited ABAC | Frozen |
| API | REST + OpenAPI 3.1 | Frozen |
| Events | RabbitMQ + versioned JSON envelope | Frozen |
| Realtime UI | Server-Sent Events | Frozen |
| System Observability | OpenTelemetry + Prometheus + Grafana + Loki | Frozen |
| Trace Backend | Tempo or Jaeger | Provisional |
| Agent Observability | LangSmith | Frozen for MVP |
| Offline Evaluation | LangSmith datasets and experiments | Frozen for MVP |
| Online Evaluation | LangSmith online evaluators | Optional |
| Local Deployment | Docker Compose | Frozen |
| Future Deployment | Kubernetes | Deferred |
| Repository | Monorepo | Frozen |
| CI | GitHub Actions | Frozen |

Exact patch versions are pinned in lockfiles, container images, and build configuration.

---

## 3. Runtime and Service Boundaries

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

Java owns ticket lifecycle, state machines, transactions, outbox, policy, approvals, tool-execution control, idempotency, audit, and public business APIs.

Python owns agent workflows, LLM calls, multi-agent coordination, structured outputs, context construction, RAG, memory processing, and evaluation.

Mandatory boundaries:

- Python does not mutate Java-owned tables.
- Java Domain does not depend on Python implementation.
- Cross-service communication uses REST or versioned events.
- Agents never directly access enterprise credentials or administrative APIs.

---

## 4. Frontend Baseline

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

**2026-09-01 architecture change** (settled when domains 09/10 were chartered, superseding the "one portal, role-based areas" sentence below): split into two independent frontend applications — `apps/employee-portal` (domain 09, employee self-service / conversational support) and `apps/support-console` (domain 10, support-staff / admin console) — not one portal with internal role-based routing. Reason: the two audiences have genuinely different mental models (an employee wants a conversation; support staff want a scannable operating console), and separating them is clearer than merging into one route/component tree; `apps/` has been two separate folders since the project's own initial scaffold, always in tension with this document's older wording, now formally reconciled in the `apps/` directory's favor. Both still share this section's technology stack, one Keycloak realm, and the same backend REST APIs — only deployment and routing trees are separate.

Rules: no direct infrastructure access; all business data comes through APIs; backend authorization is mandatory; secrets and unrestricted prompts are never displayed; SSE supports reconnect, heartbeat, and Last-Event-ID.

---

## 5. Java Core Backend Baseline

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

Package architecture:

```text
api
application
domain
infrastructure
config
```

Rules: controllers contain no business rules; APIs do not expose JPA entities; aggregates use optimistic locking; state and outbox are committed together; database transactions do not call brokers, LLMs, or remote APIs; timestamps use UTC; integration tests use Testcontainers.

---

## 6. Python Agent Runtime Baseline

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
RabbitMQ async client
```

Internal interfaces:

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

Every agent output passes a Pydantic schema. Free-form model text is not a trusted tool command. Workflows and checkpoints persist to PostgreSQL. Redis is not the sole workflow store. LangGraph and LangSmith stay behind internal interfaces.

---

## 7. Data and Infrastructure

Schema ownership:

```text
ticket.*      → Ticket Workflow
agent.*       → Agent Runtime
memory.*      → Memory & Knowledge
tool.*        → Tool Gateway
policy.*      → Policy & Approval
evaluation.*  → Evaluation
audit.*       → Audit
```

Services write only their owned schemas. Java schemas use Flyway and Python schemas use Alembic. Audit is append-only and mutable aggregates contain versions.

Redis is used for cache, rate limiting, leases, limited locks, and SSE metadata, never as the only durable store.

RabbitMQ uses at-least-once delivery with idempotent consumers, retry queues, DLQs, explicit acknowledgements, prefetch, poison-message handling, and replay.

MinIO provides local S3-compatible object storage.

---

## 8. API and Event Standards

```text
HTTPS
REST
OpenAPI 3.1
JSON
ISO 8601 UTC
/api/v1
/internal/v1
```

Event envelope:

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

Events use lowercase dot notation, JSON Schema contracts, major versions for breaking changes, minimized PII, and no credentials.

---

## 9. Authentication, Authorization, and Secrets

```text
Keycloak
OpenID Connect
OAuth 2.0
JWT
RBAC + limited ABAC
```

Roles include employee, support, administrator, security administrator, manager, and auditor. ABAC checks ticket ownership, assigned queues, target-system authority, approval expiry, ticket cancellation, and separation of duties.

Secrets never enter Git, prompts, traces, or logs.

---

## 10. Two-Layer Observability

System observability:

```text
OpenTelemetry
Prometheus
Grafana
Loki
Tempo / Jaeger
```

It owns cross-service traces, HTTP/broker/database spans, latency, queue lag, errors, logs, and infrastructure metrics.

Agent observability:

```text
LangSmith
```

It owns agent run trees, LLM calls, prompt versions, tool trajectories, retrieval, memory behavior, handoffs, token use, datasets, experiments, feedback, and evaluation.

LangSmith is not the system of record for tickets, checkpoints, approvals, policies, tool executions, audits, or long-term memory.

---

## 11. OpenTelemetry and LangSmith Correlation

Shared metadata:

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

MVP decision:

```text
OpenTelemetry SDK = system spans
LangSmith SDK = agent-semantic traces
```

A future ADR may evaluate OpenTelemetry Collector fan-out.

---

## 12. LangSmith Conventions and Data Security

Projects:

```text
opsmind-local-agent
opsmind-ci-evaluation
opsmind-demo-agent
opsmind-staging-agent
```

Trace tree:

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

Before export: PII redaction, secret redaction, credential removal, document filtering, log truncation, and prompt sanitization.

Never send passwords, tokens, API keys, cookies, private keys, authentication headers, enterprise secrets, or complete unredacted login logs.

Data classification uses `PUBLIC`, `INTERNAL`, `SENSITIVE`, and `SECRET`; `SECRET` data is never exported.

---

## 13. Sampling and Evaluation

| Scenario | Sampling |
|---|---:|
| Local Development | 100% |
| Golden Path Demo | 100% |
| Offline Evaluation | 100% |
| Unit Tests | 0% |
| Selected Integration Tests | 100% |
| Normal CI | 0% |
| Demo Environment | 25%–100% |
| Health Checks | 0% |

MVP dataset:

```text
identity-mfa-golden-dataset
```

Deterministic evaluators cover classification, root cause, tool allowlists, argument schemas, approvals, forbidden actions, and final state. LLM judges are restricted to explanation quality, grounding, handoff completeness, and clarity. Security checks never rely only on an LLM judge.

Release gates require zero policy violations and forbidden tool calls, no material regression, all critical cases passing, and controlled cost.

---

## 14. LangSmith Failure Degradation

```text
Fail-open for telemetry
Fail-closed for security
```

Trace-export failures do not block ticket workflows, but never bypass policy, approvals, credentials, or tool validation. Retries are bounded and non-blocking. Audit records are never dropped.

---

## 15. Deployment, Testing, and CI

Docker Compose runs application services, PostgreSQL, Redis, RabbitMQ, Keycloak, MinIO, OpenTelemetry Collector, Prometheus, Grafana, Loki, and Tempo or Jaeger. LangSmith uses an external workspace.

Testing:

```text
Frontend: Vitest + React Testing Library + Playwright
Java: JUnit 5 + Mockito + Testcontainers + ArchUnit
Python: pytest + httpx + LangSmith Evaluation
```

GitHub Actions runs formatting, linting, static analysis, unit, contract, integration, agent evaluation, builds, image builds, dependency scans, and secret scans.

---

## 16. Rejected and Deferred Decisions

Rejected for MVP: LangSmith replacing OpenTelemetry; OpenTelemetry replacing LangSmith evaluation; LangSmith as audit or workflow storage; one service per agent; Kafka; a separate vector database; Redis durable state; Kubernetes-first delivery; event sourcing; full CQRS.

Deferred: cloud provider, production secret manager, final trace backend, Kubernetes tooling, final LangGraph decision, LangSmith online evaluation and self-hosting, model and embedding providers, Kafka, Temporal, and physical database-per-service separation.

---

## 17. Next Step and Folder Structure

```text
docs/
└── low-level-design/
    ├── shared/
    │   ├── api/
    │   ├── data-model/
    │   ├── diagrams/
    │   ├── events/
    │   └── technology-baseline/
    │       ├── README_CN.md
    │       └── README_EN.md
    ├── domains/
    │   ├── 01-user-access-authentication/
    │   ├── 02-ticket-workflow/
    │   ├── 03-agent-runtime-orchestration/
    │   ├── 04-memory-knowledge/
    │   ├── 05-tool-integration-gateway/
    │   ├── 06-policy-approval-governance/
    │   ├── 07-evaluation-improvement/
    │   ├── 08-observability-platform/
    │   ├── 09-employee-portal/
    │   └── 10-support-console/
    ├── README_CN.md
    └── README_EN.md
```

Ticket Workflow proceeds through domain model, invariants, state machine, use cases, contracts, data, transactions, outbox, concurrency, failures, observability, class design, and testing.

---

## 18. Official References

- [LangSmith OpenTelemetry Tracing](https://docs.langchain.com/langsmith/trace-with-opentelemetry)
- [LangSmith Evaluation](https://docs.langchain.com/langsmith/evaluation)
- [Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/)
- [React 19](https://react.dev/blog/2024/12/05/react-19)
- [Vite Releases](https://vite.dev/releases)
- [Python 3.13](https://docs.python.org/3.13/)
- [PostgreSQL 18](https://www.postgresql.org/docs/18/)
- [OpenTelemetry](https://opentelemetry.io/docs/)
