# Tool Integration Gateway LLD

## Scope

This directory defines the low-level design for `05-tool-integration-gateway`. This domain converts tool intent produced by Agent Runtime into controlled, approvable, auditable, idempotent, and recoverable tool execution.

Tool Integration Gateway is the single execution boundary for external tools, internal operations tools, SaaS APIs, scripts, and automation connectors. Agents must not invoke tools directly. An agent may only ask `03-agent-runtime-orchestration` to create a Tool Request; this domain then handles risk evaluation, policy checks, approval integration, credential injection, execution scheduling, result normalization, and event publication.

This domain does not own Ticket lifecycle state and does not own Agent Workflow state. Ticket state remains owned by `02-ticket-workflow`; Agent Workflow state remains owned by `03-agent-runtime-orchestration`. This domain owns only Tool Request, Tool Execution, Connector, Credential Binding, Approval Linkage, and Tool Result state.

## Core Answers

- Can an Agent call a Tool directly? No. Agents must go through Runtime and Tool Gateway.
- A Tool Request is the tool invocation intent submitted by Runtime: what to do, why, related ticket/workflow/task, input, and required capability.
- A Tool Execution is a schedulable execution attempt for a Tool Request, including approval state, credential binding, connector selection, retry, timeout, result, and audit information.
- A Tool Connector is a controlled adapter for a concrete tool/API/script/SaaS integration. It must declare capability, risk level, input schema, output schema, timeout, retry policy, and secret requirements.
- Tool Gateway asks Policy/Approval Governance for risk and approval decisions, but does not own all governance rules.
- Credentials must not be exposed to Agent, Runtime, or Ticket Workflow. Credentials are injected only inside the Gateway execution boundary with least privilege.
- `tool.completed` means tool execution completed. It does not mean the ticket is resolved or the workflow is completed.
- Tool Execution state is separate from Ticket state and Workflow state, connected only by identifiers and events.
- Idempotency is enforced through request idempotency keys, execution attempts, connector operation keys, processed event records, and outbox records.
- Runtime crashes do not lose results; Gateway publishes `tool.completed.v1`, and Runtime consumes it idempotently.
- Gateway crashes recover through persisted Tool Request/Execution rows, leases, checkpoints, outbox replay, and connector reconciliation.

## Why A Separate Tool Gateway Exists

Allowing agents to call tools directly creates four systemic risks:

- Agents could bypass approval, risk policy, and least-privilege controls.
- External side effects would be hard to make idempotent; repeated execution could cause incidents.
- Raw credentials and sensitive tool output could leak into agent context or long-term memory.
- Ticket/Workflow domains would lack a unified audit chain proving who requested, who approved, what executed, and what happened.

Tool Gateway is therefore an isolation layer: Agent reasons and proposes intent, Runtime orchestrates, Gateway owns the execution boundary, Policy owns governance rules, and Ticket Workflow owns business state.

## Relationship With Other Domains

- `02-ticket-workflow`: consumes tool facts indirectly in business workflow, but never calls connectors directly.
- `03-agent-runtime-orchestration`: creates Tool Requests and waits for `tool.completed.v1`.
- `04-memory-knowledge`: may receive normalized tool evidence, but cannot execute tools or persist unredacted raw output.
- `06-policy-approval-governance`: provides risk decisions, approval requirements, approval results, and policy audit.
- `07-evaluation-improvement`: evaluates tool success rate, misuse rate, automation value, and connector quality.
- `08-observability-platform`: aggregates logs, metrics, traces, and audit events.

## 14 LLD Slices

1. `01-domain-model`: Tool Request, Tool Execution, Connector, Capability, Credential Binding, Tool Result.
2. `02-business-invariants`: single entry point, credential isolation, state separation, approval boundary, mandatory audit.
3. `03-state-machine`: Tool Request, Execution Attempt, Approval Linkage, Connector Health state machines.
4. `04-use-cases`: submit request, low-risk automatic execution, high-risk approval, retry, cancel, result return.
5. `05-api-contracts`: Runtime API, admin connector API, internal execution API, health API.
6. `06-event-contracts`: consume tool request / approval / policy events and publish tool lifecycle events.
7. `07-data-model`: PostgreSQL tables, unique keys, audit tables, outbox, connector registry.
8. `08-transaction-and-outbox`: request persistence, approval decision, execution result, outbox publication order.
9. `09-concurrency-and-idempotency`: claim leases, duplicate requests, connector side-effect keys, duplicate events.
10. `10-failure-handling`: connector timeout, partial side effects, poison request, reconciliation.
11. `11-security`: secret handling, RBAC/ABAC, redaction, network allowlist, audit.
12. `12-observability`: latency, success rate, approval wait, risk distribution, tracing.
13. `13-package-and-class-design`: ports/adapters, services, repositories, workers, connector SDK.
14. `14-testing-strategy`: unit, integration, contract, idempotency, security, recovery tests.

## Freeze Principles

The frozen 05 design must guarantee:

- Every tool execution is traceable to `ticketId`, `workflowInstanceId`, `agentTaskId`, and `requestedBy`.
- Every external side effect has an idempotency key and audit record.
- Every tool result has a normalized envelope, redaction status, and evidence reference.
- Any tool requiring approval waits for explicit approval from `06-policy-approval-governance`.
- Connector failure never advances Ticket state directly; it only publishes facts for Runtime/Ticket Workflow to decide on.

