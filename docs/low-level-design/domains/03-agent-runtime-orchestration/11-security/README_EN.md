# 11 Security

## Security Boundary

Agent Runtime is a high-privilege orchestration layer, but Agents themselves must not hold direct enterprise-system privileges.

Security principles:

- Agents do not call Tools directly.
- Agents do not hold third-party system credentials.
- Tool Gateway centralizes authorization, audit, rate limiting, and policy evaluation.
- Runtime stores policy snapshots and request metadata, not plaintext secrets.

## Agent Identity

Every Agent Task execution must include:

- `agentRole`
- `workerId`
- `claimToken`
- `workflowInstanceId`
- `ticketId`
- `correlationId`

Audit must answer: which Agent role produced which decision in which Workflow based on which input.

## Mandatory Tool Gateway Path

Forbidden:

- Agent Worker directly depends on Jira, Slack, Kubernetes, Cloud Provider, Database, or similar clients.
- Task handler bypasses Runtime to create external side effects.
- Checkpoint stores Tool credentials.

Allowed:

- Agent produces `ToolRequestDraft`.
- Runtime validates and persists Tool Request.
- Tool Gateway executes tool according to policy.
- Runtime consumes Tool Gateway completion event.

## Authorization

Runtime command authorization:

- start workflow: event consumer or trusted internal service only.
- pause/resume: operations, Ticket Workflow, or policy engine.
- claim/complete task: trusted worker identity.
- replay/recover: admin only.

## Data Protection

- Minimize PII in payloads.
- Checkpoint stores only information required for recovery.
- Raw long-form agent reasoning should be summarized or redacted before storage.
- Logs must not print secrets, tokens, or full tool responses.

## Audit

Must audit:

- workflow state transition
- task claim/complete/fail
- tool request created
- tool result consumed
- pause/resume command
- recovery action
- admin override
