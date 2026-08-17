# 01 Domain Model

## Aggregate Boundary

The core aggregate in Tool Integration Gateway is `ToolRequest`. It represents a tool invocation intent submitted by Agent Runtime and governed by the Gateway.

`ToolRequest` is not the external call itself. Actual calls are represented by one or more `ToolExecution` attempts. This separates intent, approval wait, connector execution, retry, and result archival.

## Core Entities

### ToolRequest

Field semantics:

- `toolRequestId`: aggregate id owned by this domain.
- `idempotencyKey`: business idempotency key supplied by Runtime.
- `ticketId` / `ticketCycleId`: related ticket identifiers; Gateway does not own ticket state.
- `workflowInstanceId` / `agentTaskId`: related Runtime identifiers; Gateway does not own workflow state.
- `requestedByType`: `AGENT`, `SYSTEM`, or `HUMAN_OPERATOR`.
- `requestedById`: requesting principal.
- `capabilityName`: required capability, for example `kubernetes.restartDeployment`.
- `toolName`: optional target tool; if omitted, Gateway chooses a connector by capability.
- `inputPayload`: normalized input.
- `reason`: auditable reason for execution.
- `riskSnapshot`: risk decision snapshot from Gateway/Policy.
- `status`: request lifecycle state.

### ToolExecution

`ToolExecution` is one execution attempt for a ToolRequest.

Key fields:

- `executionId`
- `toolRequestId`
- `attemptNumber`
- `connectorId`
- `connectorVersion`
- `operationKey`
- `leaseOwner`
- `leaseExpiresAt`
- `status`
- `startedAt`
- `completedAt`
- `timeoutAt`
- `resultEnvelopeId`

`operationKey` is the side-effect idempotency key passed to or simulated around the connector. Every connector that can mutate external state must support or emulate this key.

### ToolConnector

`ToolConnector` is a registered adapter for a concrete tool.

It must declare:

- `connectorId`
- `name`
- `version`
- `capabilities`
- `inputSchema`
- `outputSchema`
- `riskLevel`
- `requiresApproval`
- `secretRequirements`
- `networkPolicy`
- `timeoutPolicy`
- `retryPolicy`
- `healthStatus`

### Capability

Capability is the stable ability exposed to Runtime. It is not the same as a concrete tool. Runtime should submit requests by capability; Gateway decides which connector implements it.

Examples:

- `ticket.enrichFromCmdb`
- `kubernetes.getPodLogs`
- `kubernetes.restartDeployment`
- `slack.notifyChannel`
- `servicenow.createChangeRequest`

### CredentialBinding

`CredentialBinding` describes how execution obtains credentials.

Credential values are not stored in business tables. Only vault references, scopes, rotation metadata, `lastUsedAt`, and audit references are stored.

### ToolResultEnvelope

All connector output must be normalized into a Tool Result Envelope:

- `resultEnvelopeId`
- `executionId`
- `status`
- `summary`
- `structuredOutput`
- `rawOutputRef`
- `redactionStatus`
- `evidenceRefs`
- `externalResourceRefs`
- `errorCode`
- `retryable`

Raw output is not included in event payloads by default. Events carry summaries, references, and redacted structured results.

## Value Objects

- `RiskDecisionRef`: reference to the Policy/Approval risk decision.
- `ApprovalRequestRef`: reference to an approval request.
- `ConnectorInvocationSpec`: standard input passed to connectors.
- `RedactionMetadata`: output redaction and classification result.
- `AuditActor`: requester, approver, worker, and connector identity.

## Aggregate Rules

- A `ToolRequest` may have no `ToolExecution`, for example while waiting for approval or after policy denial.
- A `ToolRequest` may have multiple `ToolExecution` attempts, but only one active attempt at a time.
- A `ToolExecution` belongs to exactly one `ToolRequest`.
- `ToolConnector` is a registry/config entity and is not part of the ToolRequest aggregate.
- `CredentialBinding` is referenced by execution, but credential values must not enter request, execution, or result tables.

