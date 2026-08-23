# 02 Business Invariants

## Invariants

### INV-PG-001: 06 Performs No Business Side Effects

06 must not execute tools, mutate tickets, advance workflows, or write active memory. It produces governance facts only.

### INV-PG-002: Policy Decisions Must Be Explainable

Every decision must persist input hash, policy id/version, reason codes, constraints, and evaluatedAt.

### INV-PG-003: Approval Must Be Unforgeable

Approval Decision must come from an authorized approver or governance principal and pass signature/session/audit validation.

### INV-PG-004: Separation Of Duties Must Be Verified

Requester, executor, and approver must not violate separation-of-duties policy. High-risk overrides require independent approvers.

### INV-PG-005: Approval Is Valid Only For Matching Requests

approval granted/denied applies only to matching `approvalRequestId + sourceRequestId + requestHash`.

### INV-PG-006: Policy Versions Must Not Rewrite History

Published versions are immutable. Rule fixes require new versions.

### INV-PG-007: Denied And Expired Must Stay Distinct

Downstream domains need to know whether a request was explicitly denied, expired, cancelled, or policy denied. These must not collapse into generic denied.

### INV-PG-008: Every Governance Action Must Be Audited

policy draft/publish/deprecate, decision evaluate, approval grant/deny/expire/cancel, and override must write audit.

## Cross-Domain Boundaries

- 05 depends on 06 decisions, but 06 does not call connectors.
- 02/03 depend on approval facts, but 06 does not transition their state machines.
- 04 depends on retention/redaction policy, but 06 does not store knowledge content.

