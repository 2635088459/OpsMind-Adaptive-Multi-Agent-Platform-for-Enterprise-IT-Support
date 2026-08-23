# Policy Approval Governance LLD

## Scope

This directory defines the low-level design for `06-policy-approval-governance`. This domain owns platform policy decisions, risk classification, approval lifecycle, governance audit, separation of duties, and change control.

Policy Approval Governance does not execute tools, advance Ticket state, advance Agent Workflow state, or persist Memory. It answers whether a request is allowed, what its risk is, whether approval is required, who may approve, whether approval is valid, and how governance evidence is audited.

## Core Answers

- Policy Decision is a governance judgment for an action/request, including allow/deny, risk level, approval requirement, constraints, reason codes, and policy version.
- Approval Request is a request needing a human or governance principal decision, usually raised by Tool Gateway, Ticket Workflow, or Runtime.
- Approval Decision is the final approval outcome: granted, denied, expired, or cancelled.
- Governance Audit is the mandatory audit chain recording policy version, requester, approver, separation of duties, decision reasons, and output constraints.
- 06 does not execute tools. 05 Tool Gateway executes tools; 06 returns risk/approval/decision facts.
- 06 does not directly mutate Ticket/Workflow state. It publishes facts consumed by 02/03/05.
- High-risk tools, sensitive data access, privileged recovery, admin repair, and policy override must go through 06.
- Policy and Approval must be idempotent: repeated evaluation, repeated approval submission, and repeated decision events must not create conflicting outcomes.
- Policy version must be included in decision snapshots so historical execution remains explainable.

## Why A Separate Governance Domain Exists

If every domain implemented approval and policy locally:

- the same risk action could receive inconsistent decisions across entry points;
- separation of duties would be hard to verify uniformly;
- tool, ticket, memory, and runtime audit chains would fragment;
- policy changes could not explain historical decisions;
- high-risk overrides could not be governed consistently.

Domain 06 is therefore the owner of platform governance: rules, approvals, separation of duties, policy version, decision reasons, and audit trail.

## Relationship With Other Domains

- `02-ticket-workflow`: asks for ticket escalation, closure override, SLA exception decisions; 06 does not mutate Ticket state.
- `03-agent-runtime-orchestration`: asks for workflow pause/resume override, agent permission, automation risk decisions; 06 does not mutate Workflow state.
- `04-memory-knowledge`: asks for retention, redaction, sensitive retrieval, and memory publication policy; 06 does not write Memory.
- `05-tool-integration-gateway`: asks for tool risk decisions and approval; 06 does not execute Tools.
- `07-evaluation-improvement`: consumes governance outcomes to evaluate automation quality, approval friction, and policy effectiveness.
- `08-observability-platform`: aggregates policy decision, approval latency, governance audit, and compliance signals.

## 14 LLD Slices

1. `01-domain-model`: Policy, Rule, Policy Decision, Approval Request, Approval Decision, Governance Audit.
2. `02-business-invariants`: separation of duties, policy version, unforgeable approval, state separation.
3. `03-state-machine`: Policy lifecycle, Approval Request, Approval Decision, Override state machines.
4. `04-use-cases`: risk evaluation, approval creation, approval decision, expiry, revocation, override, policy publication.
5. `05-api-contracts`: Decision API, Approval API, Admin Policy API, Audit API.
6. `06-event-contracts`: consume governance request events and publish approval/policy/governance events.
7. `07-data-model`: PostgreSQL tables, versions, unique keys, audit, and retention.
8. `08-transaction-and-outbox`: transaction boundaries for decision/approval/audit/outbox.
9. `09-concurrency-and-idempotency`: duplicate decisions, duplicate approvals, concurrent approvals, policy version races.
10. `10-failure-handling`: policy evaluation failure, approval timeout, poison decision, degraded policy.
11. `11-security`: RBAC/ABAC, approver permissions, separation of duties, override guard, tamper-resistant audit.
12. `12-observability`: decision latency, approval SLA, deny rate, override rate, audit trace.
13. `13-package-and-class-design`: service, ports/adapters, rule evaluator, approval service.
14. `14-testing-strategy`: unit, integration, contract, security, recovery, compliance tests.

## Freeze Principles

- 06 outputs governance facts only; it must not perform business side effects.
- Every decision must persist policy version, input hash, reason code, and constraints.
- Every approval decision must validate approver permission and separation of duties.
- Every downstream domain must consume approval/policy events idempotently.
- Policy changes may affect future or non-final requests only; they must not silently rewrite historical decisions.
