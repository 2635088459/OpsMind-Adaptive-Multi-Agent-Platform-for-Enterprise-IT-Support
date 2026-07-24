# OpsMind Low-Level System Design README

> **Project:** OpsMind — Adaptive Multi-Agent Platform for Enterprise IT Support  
> **Design Phase:** Low-Level Design (LLD)  
> **MVP Scenario:** Automated investigation, approval, execution, and verification for Duo / Okta login and MFA failures  
> **Goal:** Refine the high-level architecture into implementation-ready, testable, auditable, and recoverable modules and services

---

## 1. Current Phase

OpsMind already has its first High-Level Design:

- System Context Diagram
- Six-Layer Architecture
- Eight Logical Business Domains
- MVP Golden Path
- Initial Service Boundaries
- Event-Driven Architecture Direction
- Data Ownership Principles
- High-Level Placement of Policy, Tools, Memory, and Evaluation

High-Level Design answers:

> What major parts exist, and how do they collaborate?

Low-Level Design answers:

> How is each domain implemented, what data does it own, what APIs and events does it expose, and how does it handle state, failure, concurrency, security, and testing?

---

## 2. LLD Objectives

After LLD is completed, every domain should be:

- Directly convertible into packages, classes, and services
- Mapped to database tables and fields
- Defined through API and event schemas
- Governed by explicit state transitions
- Designed for failure recovery
- Protected by security and approval rules
- Observable through logs, metrics, and traces
- Covered by unit, integration, contract, and failure tests
- Clearly divided into MVP and future scope

---

## 3. Recommended Design Order

Do not design the eight domains equally or simply follow their numeric order.

```text
Group 1: Core Business Path
1. Ticket and Business Workflow
2. Agent Runtime and Task Orchestration
3. Tool Integration and Execution Gateway
4. Policy, Approval, and Security Governance

Group 2: Intelligence
5. Memory and Enterprise Knowledge

Group 3: User and Platform Support
6. User Access and Authentication
7. Observability and Platform Infrastructure

Group 4: Quality and Evolution
8. Evaluation and Controlled Improvement
```

The first objective is to complete:

```text
Ticket
→ Agent Investigation
→ Policy Check
→ Human Approval
→ Tool Execution
→ Verification
→ Resolution
```

---

## 4. Shared Domain Design Template

Every domain document should contain:

1. Purpose
2. Responsibilities
3. Non-responsibilities
4. Internal Components
5. Core Workflows
6. State Model
7. Data Ownership
8. API Contracts
9. Event Contracts
10. Security Rules
11. Failure Handling
12. Observability
13. Testing Strategy
14. MVP Scope
15. Future Scope
16. Open Questions

---

# 5. Ticket and Business Workflow

## 5.1 Purpose

The ticket is the business carrier for every user request, investigation, approval, action, verification, and resolution.

Ticket Service owns business state. It does not own LLM reasoning or enterprise tool execution.

## 5.2 Responsibilities

- Create, retrieve, and update tickets
- Enforce the Ticket State Machine
- Store user messages
- Store state history
- Manage SLA
- Cancel, escalate, resolve, close, and reopen tickets
- Publish ticket events
- Use a Transactional Outbox

## 5.3 Non-responsibilities

- Calling LLMs
- Querying Okta or Duo
- Producing root-cause hypotheses
- Executing privileged operations
- Approving sensitive actions
- Writing long-term memory

## 5.4 Internal Components

```text
TicketController
TicketApplicationService
TicketDomainService
TicketStateMachine
TicketRepository
TicketMessageService
TicketAssignmentService
SlaService
TicketStatusHistoryService
OutboxService
OutboxPublisher
```

## 5.5 Ticket Aggregate

```json
{
  "ticketId": "INC-2048",
  "requesterId": "user-1024",
  "title": "Cannot log in to Housing Portal",
  "description": "Duo authentication keeps failing",
  "category": "IDENTITY_ACCESS",
  "subcategory": "MFA_FAILURE",
  "priority": "MEDIUM",
  "status": "INVESTIGATING",
  "workflowId": "wf-7788",
  "assignedTeam": "IAM_SUPPORT",
  "version": 4,
  "createdAt": "2026-07-21T10:20:00Z",
  "updatedAt": "2026-07-21T10:24:00Z"
}
```

## 5.6 Ticket State Machine

```text
NEW
→ TRIAGING
→ INVESTIGATING
→ WAITING_FOR_USER
→ WAITING_FOR_APPROVAL
→ EXECUTING
→ VERIFYING
→ RESOLVED
→ CLOSED
```

Exception states:

```text
ESCALATED
FAILED
CANCELLED
REOPENED
```

## 5.7 Valid Transitions

| Current | Target | Trigger |
|---|---|---|
| NEW | TRIAGING | `ticket.created` |
| TRIAGING | INVESTIGATING | Classification completed |
| INVESTIGATING | WAITING_FOR_USER | Missing information |
| INVESTIGATING | WAITING_FOR_APPROVAL | Privileged action required |
| WAITING_FOR_APPROVAL | EXECUTING | Approval granted |
| EXECUTING | VERIFYING | Tool execution completed |
| VERIFYING | RESOLVED | Verification succeeded |
| VERIFYING | INVESTIGATING | Verification failed but investigation can continue |
| RESOLVED | CLOSED | User confirmation or automatic closure |
| RESOLVED | REOPENED | User reports recurrence |

Illegal transitions include:

```text
NEW → RESOLVED
CANCELLED → EXECUTING
CLOSED → INVESTIGATING
WAITING_FOR_APPROVAL → RESOLVED
```

## 5.8 Tables

```text
ticket.tickets
ticket.ticket_messages
ticket.ticket_status_history
ticket.ticket_assignments
ticket.sla_records
ticket.outbox_events
```

## 5.9 APIs

```http
POST /api/v1/tickets
GET  /api/v1/tickets/{ticketId}
GET  /api/v1/tickets
POST /api/v1/tickets/{ticketId}/messages
POST /api/v1/tickets/{ticketId}/cancel
POST /api/v1/tickets/{ticketId}/reopen
POST /internal/v1/tickets/{ticketId}/transitions
```

## 5.10 Published Events

```text
ticket.created
ticket.updated
ticket.user_replied
ticket.cancelled
ticket.reopened
ticket.status_changed
ticket.resolved
ticket.closed
```

## 5.11 Consumed Events

```text
ticket.classified
approval.requested
approval.granted
approval.rejected
tool.execution.completed
tool.execution.failed
verification.completed
agent.workflow.failed
```

## 5.12 Concurrency Control

Use optimistic locking:

```text
UPDATE ticket
SET status = ?, version = version + 1
WHERE ticket_id = ? AND version = ?
```

A failed update reloads the aggregate and revalidates the transition.

## 5.13 Testing

- Valid state transitions
- Illegal state transitions
- Duplicate events
- Concurrent updates
- Transactional Outbox
- Cancellation blocks execution
- Failed verification cannot close a ticket

---

# 6. Agent Runtime and Task Orchestration

## 6.1 Purpose

Agent Runtime is the execution and control platform for all agents. It handles planning, delegation, state, pause/resume, retry, cost control, and handoffs.

## 6.2 Responsibilities

- Create agent workflows
- Load agent definitions
- Schedule agent tasks
- Manage worker pools
- Persist execution state and checkpoints
- Pause and resume workflows
- Enforce timeout, retry, and budget policies
- Merge multi-agent results
- Detect no-progress loops
- Record model calls and trajectories

## 6.3 Non-responsibilities

- Direct ticket-table updates
- Approving its own actions
- Direct Okta or Duo access
- Storing administrator credentials
- Self-certifying business success

## 6.4 Internal Components

```text
WorkflowManager
WorkflowStateMachine
AgentCoordinator
TaskPlanner
AgentRegistry
WorkerDispatcher
CheckpointManager
ContextBuilder
ModelGateway
BudgetController
RetryManager
HandoffManager
ProgressDetector
StructuredOutputValidator
```

## 6.5 MVP Agents

```text
Triage Agent
Identity Agent
Knowledge Agent
Resolution Agent
Verification Agent
```

## 6.6 Workflow Model

```json
{
  "workflowId": "wf-7788",
  "ticketId": "INC-2048",
  "workflowType": "IDENTITY_MFA_INVESTIGATION",
  "status": "RUNNING",
  "currentStep": "IDENTITY_CHECK",
  "iterationCount": 3,
  "toolCallCount": 5,
  "tokenCostUsd": 0.18,
  "maxIterations": 10,
  "maxToolCalls": 20,
  "budgetUsd": 1.00,
  "version": 7
}
```

## 6.7 Workflow State Machine

```text
PENDING
→ RUNNING
→ WAITING_FOR_EVENT
→ PAUSED
→ RUNNING
→ COMPLETED
```

Exception states:

```text
RETRYING
TIMED_OUT
FAILED
CANCELLED
```

## 6.8 Checkpoint Content

```text
workflow_id
current_step
completed_tasks
pending_tasks
facts
hypotheses
rejected_hypotheses
tool_results
approval_request
token_usage
iteration_count
next_action
created_at
```

## 6.9 Pause and Resume

```text
Resolution Agent proposes Duo reset
→ Runtime requests policy evaluation
→ Approval required
→ Save checkpoint
→ Workflow = PAUSED
→ Wait for approval.granted
→ Load checkpoint
→ Validate ticket is still active
→ Resume tool execution
```

## 6.10 Task Idempotency

Recommended key:

```text
workflow_id + agent_name + task_type + task_version
```

## 6.11 Loop Limits

```yaml
max_iterations: 10
max_tool_calls: 20
max_cost_usd: 1.00
timeout_minutes: 15
no_progress_limit: 2
```

## 6.12 Agent Output Schema

```json
{
  "agent": "identity-agent",
  "taskId": "task-301",
  "status": "COMPLETED",
  "facts": [
    {
      "type": "DUO_ENROLLMENT",
      "value": "EXPIRED",
      "source": "tool:get_duo_enrollment"
    }
  ],
  "hypotheses": [
    {
      "cause": "EXPIRED_DUO_ENROLLMENT",
      "confidence": 0.91
    }
  ],
  "recommendedNextActions": [
    "SEARCH_SIMILAR_TICKETS",
    "REQUEST_DUO_RESET"
  ]
}
```

## 6.13 Tables

```text
agent.workflows
agent.workflow_steps
agent.agent_tasks
agent.agent_task_attempts
agent.checkpoints
agent.agent_handoffs
agent.model_calls
agent.task_idempotency
```

## 6.14 Published Events

```text
agent.workflow.started
agent.workflow.paused
agent.workflow.resumed
agent.workflow.completed
agent.workflow.failed
agent.task.requested
agent.task.completed
agent.task.failed
resolution.proposed
verification.requested
```

## 6.15 Consumed Events

```text
ticket.created
ticket.user_replied
ticket.cancelled
approval.granted
approval.rejected
tool.execution.completed
tool.execution.failed
```

## 6.16 Testing

- Workflow transitions
- Worker crash recovery
- Checkpoint resume
- Duplicate task deduplication
- Structured-output validation
- Budget limits
- No-progress detection
- Parallel result merging
- Conflicting agent results
- Ticket cancellation

---

# 7. Tool Integration and Execution Gateway

## 7.1 Purpose

Tool Gateway is the only path between agents and enterprise systems.

## 7.2 Responsibilities

- Register tools
- Validate input schemas
- Validate policy decisions
- Validate approvals
- Enforce idempotency
- Inject credentials securely
- Route to connectors
- Persist execution records
- Normalize external responses
- Handle timeout and retry behavior

## 7.3 Internal Components

```text
ToolRegistry
ToolRequestValidator
PolicyClient
ApprovalValidator
IdempotencyManager
CredentialProvider
ConnectorRouter
ExecutionManager
ToolResultNormalizer
ExecutionRepository
ConnectorHealthMonitor
```

## 7.4 MVP Tools

```text
get_account_status
check_group_membership
get_duo_enrollment
query_login_failures
reset_duo_enrollment
verify_login
send_user_instructions
```

## 7.5 Tool Definition

```json
{
  "toolName": "reset_duo_enrollment",
  "version": "1.0",
  "riskLevel": "MEDIUM",
  "approvalRequired": true,
  "requiredRole": "IT_ADMIN",
  "timeoutSeconds": 20,
  "retryPolicy": "VERIFY_BEFORE_RETRY",
  "idempotencyRequired": true
}
```

## 7.6 Execution Flow

```text
Receive request
→ Validate schema
→ Validate caller identity
→ Load tool definition
→ Check policy decision
→ Check approval
→ Check idempotency
→ Load credential
→ Call connector
→ Normalize result
→ Save execution record
→ Publish completion event
```

## 7.7 Tool Request

```json
{
  "executionId": "exec-6001",
  "ticketId": "INC-2048",
  "workflowId": "wf-7788",
  "toolName": "reset_duo_enrollment",
  "toolVersion": "1.0",
  "arguments": {
    "userId": "user-1024"
  },
  "approvalId": "approval-81",
  "idempotencyKey": "INC-2048:wf-7788:reset_duo:user-1024:v1",
  "requestedBy": "resolution-agent-v1"
}
```

## 7.8 Lost Response Recovery

1. Do not immediately retry.
2. Query the external system state.
3. Recover the previous result if the action completed.
4. Retry only when the action is confirmed incomplete.
5. Escalate when the state is unknown.

## 7.9 Credential Isolation

- Credentials exist only in Credential Provider.
- Credentials never enter prompts.
- Agents cannot read tokens.
- Logs never contain secrets.
- Connectors use least-privilege identities.

## 7.10 Tables

```text
tool.tool_registry
tool.tool_executions
tool.tool_execution_attempts
tool.execution_idempotency
tool.connector_health
```

## 7.11 Published Events

```text
tool.execution.started
tool.execution.completed
tool.execution.failed
tool.execution.unknown
connector.health_changed
```

## 7.12 Testing

- Schema validation
- Unapproved write rejection
- Expired approval rejection
- Idempotency
- Lost-response recovery
- Credential leakage
- Connector timeout
- Circuit breaker
- Read-tool retry
- Write-tool verify-before-retry

---

# 8. Policy, Approval, and Security Governance

## 8.1 Purpose

Separate action proposal, authorization, approval, and execution.

## 8.2 Responsibilities

- Risk classification
- RBAC / ABAC
- Approval requests
- Approval lifecycle
- Approver eligibility
- Policy decisions
- Guardrails
- Kill switch
- Audit events

## 8.3 Internal Components

```text
PolicyEngine
RiskClassifier
AuthorizationEvaluator
ApprovalManager
ApprovalStateMachine
ApprovalExpirationScheduler
SeparationOfDutiesValidator
AuditWriter
GuardrailEngine
KillSwitchManager
```

## 8.4 Risk Levels

### Low

- Read account state
- Query logs
- Search knowledge
- Send standard instructions

### Medium

- Reset MFA
- Unlock account
- Clear session

### High

- Modify group membership
- Grant administrator privileges
- Perform bulk account operations

### Forbidden

- Delete critical accounts
- Bypass MFA
- Export secrets
- Disable auditing
- Allow agents to elevate their own privileges

## 8.5 Policy Decision

```json
{
  "policyDecisionId": "pd-401",
  "action": "reset_duo_enrollment",
  "riskLevel": "MEDIUM",
  "decision": "REQUIRES_APPROVAL",
  "requiredRole": "IT_ADMIN",
  "approvalTtlMinutes": 30,
  "reason": "The action changes user authentication state",
  "policyVersion": "2026.07.1"
}
```

## 8.6 Approval States

```text
PENDING
APPROVED
REJECTED
EXPIRED
CANCELLED
EXECUTED
```

## 8.7 Separation of Duties

- Users cannot approve privileged actions on themselves.
- Agents cannot approve write operations.
- High-risk privilege changes may require two approvers.
- Approvers must be authorized for the target system.

## 8.8 Tables

```text
policy.policies
policy.policy_versions
policy.policy_decisions
policy.approval_requests
policy.approval_decisions
policy.role_bindings
policy.kill_switches
audit.audit_events
```

## 8.9 Published Events

```text
approval.requested
approval.granted
approval.rejected
approval.expired
approval.cancelled
policy.denied
kill_switch.activated
```

## 8.10 Testing

- Low-risk automatic approval
- Medium-risk approval requirement
- Forbidden action denial
- Approval expiration
- Duplicate approval
- Ticket cancellation invalidates approval
- Self-approval rejection
- Kill switch
- Policy version tracking

---

# 9. Memory and Enterprise Knowledge

## 9.1 Purpose

Maintain continuity inside the current ticket and reuse validated experience across future tickets.

## 9.2 Internal Components

```text
WorkingMemoryStore
MemoryExtractor
MemoryValidator
MemoryConsolidator
ConflictDetector
RetrievalEngine
KnowledgeIngestionPipeline
DocumentChunker
EmbeddingService
MemoryVersionManager
RetentionManager
PiiRedactor
```

## 9.3 Working Memory

```json
{
  "ticketId": "INC-2048",
  "version": 6,
  "facts": [],
  "hypotheses": [],
  "rejectedHypotheses": [],
  "completedTasks": [],
  "pendingTasks": [],
  "toolResults": [],
  "approvalDecisions": [],
  "contextSummary": ""
}
```

## 9.4 Long-Term Memory Types

- Episodic Memory
- Semantic Memory
- Procedural Memory
- Organizational Memory
- Agent Performance Memory

## 9.5 Memory Write Pipeline

```text
Ticket resolved
→ Extract candidate
→ Remove PII
→ Validate evidence
→ Deduplicate
→ Detect conflicts
→ Score confidence
→ Evaluate usefulness
→ Store versioned memory
```

## 9.6 Retrieval Scoring

```text
semantic_similarity
category_match
application_match
recency
source_trust
human_validation
resolution_success
```

## 9.7 Tables

```text
memory.working_memory
memory.memories
memory.memory_versions
memory.memory_sources
memory.memory_conflicts
memory.knowledge_documents
memory.document_chunks
memory.embeddings
memory.retrieval_logs
```

## 9.8 Testing

- Working-memory versioning
- Concurrent merge
- PII redaction
- Duplicate memory
- Conflicting memory
- Retrieval precision
- Provenance
- Expiration
- Degraded mode
- Invalid memory rejection

---

# 10. User Access and Authentication

## 10.1 Internal Components

```text
EmployeePortal
AdminConsole
AuthenticationClient
AuthorizationGuard
TicketConversationUI
ApprovalCenter
InvestigationTimeline
AuditViewer
MetricsDashboard
RealtimeUpdateClient
```

## 10.2 Employee Pages

- Create Ticket
- My Tickets
- Ticket Detail
- Reply to Agent
- Upload Evidence
- Confirm Resolution
- Reopen Ticket

## 10.3 IT Administrator Pages

- Ticket Queue
- Approval Center
- Investigation Timeline
- Evidence Viewer
- Tool Execution History
- Audit Viewer
- Memory Evidence

## 10.4 Manager Pages

- SLA Dashboard
- Resolution Metrics
- Escalation Rate
- Agent Performance
- Approval Waiting Time

## 10.5 Authentication

- OIDC
- Authorization Code Flow
- Access Tokens
- Refresh Tokens
- Role Claims
- Service Identities

## 10.6 Realtime Updates

SSE is recommended for the MVP:

```text
ticket.status_changed
approval.requested
tool.execution.completed
verification.completed
```

## 10.7 Security Requirements

- Users can view only their own tickets.
- IT Support can view assigned queues.
- IT Administrators approve only authorized operations.
- Auditors are read-only.
- Credentials are never displayed.
- Internal prompts are not shown to end users.

---

# 11. Observability and Platform Infrastructure

## 11.1 Correlation Identifiers

```text
trace_id
correlation_id
ticket_id
workflow_id
agent_task_id
tool_execution_id
approval_id
```

## 11.2 Logs

Structured logs should contain:

```json
{
  "timestamp": "",
  "service": "",
  "level": "",
  "traceId": "",
  "ticketId": "",
  "workflowId": "",
  "event": "",
  "message": "",
  "errorCode": ""
}
```

Never log:

- Passwords
- Access tokens
- API secrets
- Full PII
- Unredacted prompts

## 11.3 Metrics

### Business

- Ticket volume
- Mean time to resolution
- SLA compliance
- Reopen rate
- Human escalation rate
- Approval waiting time

### Agent

- Agent success rate
- Model latency
- Token cost
- Tool calls
- Loop iterations
- Workflow resumes
- Memory retrieval hit rate
- Verification failure rate

### Infrastructure

- API latency
- Error rate
- Queue lag
- Worker utilization
- Database connections
- Redis health
- Connector health

## 11.4 MVP Infrastructure

```text
PostgreSQL + pgvector
Redis
RabbitMQ
OpenTelemetry Collector
Prometheus
Grafana
Loki
Tempo or Jaeger
Docker Compose
```

---

# 12. Evaluation and Controlled Improvement

## 12.1 Internal Components

```text
DatasetRegistry
TestCaseRunner
DeterministicGraders
LlmJudge
PolicyComplianceGrader
TrajectoryEvaluator
RegressionComparator
AgentVersionRegistry
ImprovementProposalGenerator
CanaryManager
RollbackManager
```

## 12.2 Evaluation Dimensions

- Ticket classification accuracy
- Root-cause accuracy
- Tool selection correctness
- Tool argument correctness
- Policy compliance
- Memory retrieval precision
- Resolution success
- Reopen rate
- Human escalation rate
- Token cost
- Latency
- Handoff information loss

## 12.3 Test Case Schema

```json
{
  "caseId": "eval-001",
  "userRequest": "Duo keeps failing",
  "mockSystemState": {
    "accountStatus": "ACTIVE",
    "groupMembership": "CORRECT",
    "duoEnrollment": "EXPIRED"
  },
  "expectedCategory": "IDENTITY_ACCESS",
  "expectedRootCause": "EXPIRED_DUO_ENROLLMENT",
  "allowedTools": [
    "get_account_status",
    "check_group_membership",
    "get_duo_enrollment",
    "reset_duo_enrollment"
  ],
  "requiredApproval": true,
  "verificationCondition": "LOGIN_SUCCESS"
}
```

## 12.4 Ablation Study

```text
Baseline: Single Agent
Version A: Single Agent + RAG
Version B: Single Agent + Memory
Version C: Multi-Agent + Memory
Full: Multi-Agent + Memory + Policy + Improvement
```

## 12.5 Improvement Flow

```text
Collect traces
→ Classify failures
→ Generate candidate
→ Create version
→ Run benchmark
→ Compare with baseline
→ Detect regressions
→ Human approval
→ Canary
→ Promote or rollback
```

## 12.6 Release Gates

- Root-cause accuracy does not decrease
- Policy violations remain zero
- False tool executions do not increase
- Resolution rate improves or remains stable
- Token-cost increase remains below threshold
- All critical tests pass

---

# 13. Service Communication Standards

## 13.1 Synchronous Communication

Use synchronous APIs for immediate results:

- Portal → Ticket Service
- Agent Runtime → Memory Search
- Agent Runtime → Policy Evaluation
- Tool Gateway → Mock Enterprise API
- Admin Console → Approval API

REST is sufficient for the MVP.

## 13.2 Asynchronous Communication

```text
ticket.created
agent.workflow.started
agent.task.completed
approval.requested
approval.granted
tool.execution.completed
verification.completed
ticket.resolved
memory.candidate.created
evaluation.requested
```

## 13.3 Event Envelope

```json
{
  "eventId": "evt-1001",
  "eventType": "approval.granted",
  "eventVersion": "1.0",
  "occurredAt": "2026-07-21T10:30:00Z",
  "producer": "policy-service",
  "traceId": "trace-abc",
  "correlationId": "INC-2048",
  "ticketId": "INC-2048",
  "workflowId": "wf-7788",
  "payload": {}
}
```

Every event defines:

- Producer
- Consumers
- Version
- Payload
- Idempotency key
- Ordering
- Retry
- DLQ
- PII classification

---

# 14. Data Ownership and Consistency

## 14.1 Schema Ownership

```text
ticket.*      → Ticket Service
agent.*       → Agent Runtime
memory.*      → Memory Service
tool.*        → Tool Gateway
policy.*      → Policy Service
evaluation.*  → Evaluation Service
audit.*       → Audit Module
```

## 14.2 Rules

- Services write only their own schema.
- Cross-service updates use APIs or events.
- The MVP may share one PostgreSQL instance.
- Audit data is append-only.
- Critical writes use versions or idempotency keys.

## 14.3 Consistency Model

- Strong local transactions inside a service.
- Eventual consistency across services.
- Transactional Outbox for publication.
- At-least-once delivery.
- Idempotent consumers.
- Verify-before-retry for privileged external operations.

---

# 15. Failure Handling and Recovery

| Failure | Handling |
|---|---|
| LLM timeout | Retry, fallback, or escalation |
| Invalid JSON | Repair; fail task if unrecoverable |
| Worker crash | Resume from checkpoint |
| Duplicate event | Idempotency |
| Event publication failure | Transactional Outbox |
| Duplicate approval | Optimistic locking |
| Lost tool response | Verify external state |
| Memory unavailable | Degraded mode |
| Agent loop | Iteration, cost, and no-progress limits |
| Repeated failure | DLQ and human escalation |
| Verification failure | Return to investigation |
| Ticket cancellation | Cancel pending work |

---

# 16. Testing Strategy

## 16.1 Unit Tests

- State machines
- Policy rules
- Schema validation
- Memory scoring
- Idempotency
- Retry logic

## 16.2 Integration Tests

- Ticket → Agent Workflow
- Agent → Policy
- Policy → Approval
- Tool Gateway → Mock Duo
- Verification → Ticket Resolution
- Memory Candidate Creation

## 16.3 Contract Tests

- REST APIs
- Event schemas
- Connector contracts
- Agent structured output

## 16.4 Failure Injection

- Kill Agent Worker
- Delay RabbitMQ
- Drop Tool Response
- Stop Memory Service
- Return invalid model JSON
- Duplicate events
- Expire approvals

## 16.5 End-to-End

```text
Create Ticket
→ Investigate
→ Approve
→ Reset
→ Verify
→ Resolve
→ Write Memory
→ Evaluate
```

---

# 17. Recommended Implementation Order

## Phase 1 — Ticket Foundation

- Ticket Aggregate
- State Machine
- Repository
- REST API
- Outbox

## Phase 2 — Agent Runtime Skeleton

- Workflow
- Task
- Worker
- Checkpoint
- Triage Agent

## Phase 3 — Mock Enterprise Tools

- Mock Okta
- Mock Duo
- Tool Registry
- Query Tools

## Phase 4 — Policy and Approval

- Risk Rules
- Approval API
- Workflow Pause / Resume

## Phase 5 — Write Tool and Verification

- Duo Reset
- Idempotency
- Login Verification
- Ticket Resolution

## Phase 6 — Memory

- Working Memory
- Knowledge RAG
- Memory Candidate

## Phase 7 — Observability

- Tracing
- Metrics
- Agent Timeline

## Phase 8 — Evaluation

- Dataset
- Graders
- Baseline Comparison

---

# 18. LLD Completion Criteria

A domain is complete only when:

- [ ] Purpose is defined
- [ ] Responsibilities are defined
- [ ] Non-responsibilities are defined
- [ ] Internal components are defined
- [ ] Core workflows are defined
- [ ] State model is defined
- [ ] Data ownership is defined
- [ ] API contracts are defined
- [ ] Event contracts are defined
- [ ] Security rules are defined
- [ ] Failure handling is defined
- [ ] Observability is defined
- [ ] Testing strategy is defined
- [ ] MVP scope is defined
- [ ] Future scope is defined
- [ ] Open questions are recorded

---

# 19. Recommended Repository Structure

```text
opsmind-adaptive-multi-agent-platform/
├── apps/
│   ├── employee-portal/
│   └── support-console/
├── docs/
│   ├── adr/
│   ├── high-level-design/
│   │   ├── README_CN.md
│   │   └── README_EN.md
│   ├── low-level-design/
│   │   ├── shared/
│   │   │   ├── api/
│   │   │   ├── data-model/
│   │   │   ├── diagrams/
│   │   │   ├── events/
│   │   │   └── technology-baseline/
│   │   ├── domains/
│   │   │   ├── 01-user-access-authentication/
│   │   │   ├── 02-ticket-workflow/
│   │   │   ├── 03-agent-runtime-orchestration/
│   │   │   ├── 04-memory-knowledge/
│   │   │   ├── 05-tool-integration-gateway/
│   │   │   ├── 06-policy-approval-governance/
│   │   │   ├── 07-evaluation-improvement/
│   │   │   └── 08-observability-platform/
│   │   ├── README_CN.md
│   │   └── README_EN.md
│   ├── implementation-plans/
│   │   └── domains/
│   │       └── 02-ticket-workflow/
│   ├── specs/
│   │   └── domains/
│   │       └── 02-ticket-workflow/
│   └── traceability/
│       └── 02-ticket-workflow/
├── services/
│   └── ticket-workflow-service/
├── packages/
│   ├── event-contracts/
│   ├── api-contracts/
│   └── test-support/
├── infrastructure/
│   ├── docker-compose/
│   ├── postgres/
│   ├── rabbitmq/
│   ├── keycloak/
│   └── observability/
├── tests/
│   ├── end-to-end/
│   ├── contract/
│   ├── performance/
│   ├── chaos/
│   └── security/
└── README.md
```

---

# 20. Next Step

The first detailed domain document should be:

```text
docs/low-level-design/domains/02-ticket-workflow/
```

Then continue in this order:

```text
03-agent-runtime-orchestration/
05-tool-integration-gateway/
06-policy-approval-governance/
04-memory-knowledge/
01-user-access-authentication/
08-observability-platform/
07-evaluation-improvement/
```

Ticket Workflow must be stable before the remaining domains can reliably define their state changes, events, and completion behavior.
