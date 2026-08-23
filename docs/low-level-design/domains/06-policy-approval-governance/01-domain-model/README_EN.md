# 01 Domain Model

## Aggregate Boundary

The core aggregates in 06 are `ApprovalRequest` and `PolicyDecision`.

`PolicyDecision` is an immutable snapshot of a governance judgment. `ApprovalRequest` is an approval process waiting for a human or governance principal decision. They may be linked, but are not strictly one-to-one: a decision may not require approval, and an approval request may carry multiple constraints.

## Core Entities

### Policy

Policy is a versioned collection of governance rules.

Field semantics:

- `policyId`
- `policyName`
- `version`
- `status`
- `scope`
- `rules`
- `effectiveFrom`
- `effectiveTo`
- `createdBy`
- `publishedBy`

### PolicyRule

Rule is an evaluable unit inside a policy, expressing condition, effect, risk, approval requirement, and constraints.

### PolicyDecision

Field semantics:

- `policyDecisionId`
- `decisionKey`
- `inputHash`
- `subjectType`
- `subjectId`
- `actionType`
- `resourceType`
- `resourceId`
- `tenantId`
- `effect`
- `riskLevel`
- `approvalRequired`
- `constraints`
- `reasonCodes`
- `policyId`
- `policyVersion`
- `expiresAt`

Once final, a `PolicyDecision` must not be silently modified; a new decision must be produced instead.

### ApprovalRequest

Field semantics:

- `approvalRequestId`
- `requestKey`
- `sourceDomain`
- `sourceRequestId`
- `ticketId`
- `workflowInstanceId`
- `toolRequestId`
- `requestedBy`
- `approvalType`
- `riskLevel`
- `constraints`
- `status`
- `expiresAt`

### ApprovalDecision

Field semantics:

- `approvalDecisionId`
- `approvalRequestId`
- `decision`
- `decidedBy`
- `decidedAt`
- `reason`
- `conditions`
- `separationOfDutiesCheck`

### GovernanceAudit

Records audit facts for policy evaluation, approval lifecycle, override, admin change, and event publication.

## Value Objects

- `RiskLevel`: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
- `DecisionEffect`: `ALLOW`, `DENY`, `REQUIRE_APPROVAL`, `ALLOW_WITH_CONSTRAINTS`.
- `ApprovalStatus`: `REQUESTED`, `APPROVED`, `DENIED`, `EXPIRED`, `CANCELLED`.
- `ReasonCode`: machine-readable governance reason.
- `Constraint`: downstream restriction such as read-only, time window, max retry, or verification required.

