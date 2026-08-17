# 02 Business Invariants

## Invariants

### INV-TG-001: Tool Gateway Is The Only Tool Execution Entry Point

Agent, Runtime, Ticket Workflow, and Memory Knowledge must not execute external tools directly. They may submit requests, consume events, or read results.

### INV-TG-002: Tool State Is Separate From Ticket/Workflow State

Tool Request/Execution state transitions must not directly modify ticket state or workflow state. Gateway only publishes facts; domains 02 and 03 make their own decisions.

### INV-TG-003: Every External Side Effect Must Be Idempotent

Every connector that may mutate an external system must have an `operationKey`. If the target system does not support idempotency keys, Gateway must persist reconciliation metadata and mark the connector as `EMULATED_IDEMPOTENCY`.

### INV-TG-004: Credentials Must Not Leak

Credential values must not enter:

- Agent prompt/context
- Runtime checkpoint
- Ticket comment
- Memory document
- Event payload
- Application log

Credentials may exist only transiently inside connector invocation.

### INV-TG-005: Approval Cannot Be Bypassed

When a risk decision requires approval, Tool Request must enter `WAITING_APPROVAL` until a valid `approval.granted.v1` or `approval.denied.v1` is received.

### INV-TG-006: Audit Records Are Mandatory

The following actions must create audit records:

- request accepted/rejected
- policy decision received
- approval requested/granted/denied
- credential binding resolved
- execution started/completed/failed/cancelled
- result redacted/published
- connector disabled/enabled

### INV-TG-007: Raw Output Is Not Published By Default

`tool.completed.v1` carries only summary, redacted structured output, evidence refs, and error metadata by default. Raw output can be read only through controlled storage references.

### INV-TG-008: Connector Schemas Must Be Versioned

Every connector input/output schema must be versioned. Tool Request records the schema version used at submission time so historical requests remain interpretable after connector upgrades.

### INV-TG-009: Connector Capability Is Not Permission

Runtime visibility of a capability does not mean an Agent may execute it. Execution combines actor, tenant, ticket scope, risk, policy, and credential binding.

### INV-TG-010: Failure Facts Must Stay Specific

Connector timeout, policy denial, approval denial, non-retryable failure, and partial side effect must remain distinguishable. They must not be collapsed into a generic failure.

## Cross-Domain Boundaries

- 03 can create Tool Requests, but cannot choose credentials.
- 05 can publish `tool.completed.v1`, but cannot complete workflows.
- 06 can approve or deny high-risk actions, but cannot execute tools directly.
- 04 can persist redacted tool evidence, but cannot store secrets or raw output.

