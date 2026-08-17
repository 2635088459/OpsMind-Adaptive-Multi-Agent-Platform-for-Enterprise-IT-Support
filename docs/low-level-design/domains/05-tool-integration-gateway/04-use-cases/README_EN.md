# 04 Use Cases

## UC-TG-001: Runtime Submits Tool Request

1. Runtime calls Gateway API with `capabilityName`, input, reason, ticket/workflow/task refs, and idempotency key.
2. Gateway validates schema, actor, capability, and idempotency.
3. Gateway persists ToolRequest.
4. Gateway publishes `tool.request.accepted.v1` or returns a rejected result.

## UC-TG-002: Low-Risk Read-Only Tool Executes Automatically

1. Gateway computes risk decision.
2. If policy marks it as low-risk/no-approval, ToolRequest enters `QUEUED`.
3. Worker claims an execution attempt.
4. Connector performs the read-only call.
5. Gateway normalizes, redacts, and persists the result.
6. Gateway publishes `tool.completed.v1`.

## UC-TG-003: High-Risk Change Tool Requires Approval

1. Gateway receives request and identifies a high-risk capability.
2. Gateway calls domain 06 to create an approval request or publishes an approval requested event.
3. ToolRequest enters `WAITING_APPROVAL`.
4. After consuming `approval.granted.v1`, Gateway moves to `QUEUED`.
5. After consuming `approval.denied.v1`, Gateway moves to `APPROVAL_DENIED` and publishes `tool.completed.v1` with denied status.

## UC-TG-004: Connector Fails And Retries

1. Connector returns retryable error or timeout.
2. Gateway persists attempt failure.
3. Gateway creates the next attempt based on retry policy.
4. If max attempts are reached, ToolRequest enters `TERMINAL_FAILED`.
5. Gateway publishes final `tool.completed.v1`.

## UC-TG-005: Partial Side Effect Reconciliation

1. Connector call times out or returns uncertain status.
2. Gateway marks execution as `PARTIAL_SIDE_EFFECT` or `RECONCILING`.
3. Reconciliation worker queries the external system or connector status endpoint.
4. If success is confirmed, completed result is persisted.
5. If failure is confirmed and retry is allowed, a new attempt is created.
6. If still uncertain, Gateway publishes uncertain result and Runtime/Ticket Workflow decide on human intervention.

## UC-TG-006: Cancel Tool Request

1. Runtime or human operator requests cancellation.
2. Gateway validates requester permission and current state.
3. If execution has not started, ToolRequest enters `CANCELLED`.
4. If executing, ToolRequest enters `CANCEL_REQUESTED` and Gateway calls connector cancel hook.
5. Gateway finally publishes `tool.cancelled.v1` or `tool.completed.v1` with cancellation metadata.

## UC-TG-007: Admin Registers New Connector

1. Admin submits connector manifest.
2. Gateway validates schema, capability, risk, secret requirements, and network policy.
3. Gateway persists connector registry version.
4. Gateway publishes `tool.connector.registered.v1`.
5. The connector enters `ACTIVE` or `DISABLED` depending on policy and health check.

## UC-TG-008: Tool Result Enters Memory Knowledge

1. Gateway completes result normalization.
2. Gateway persists redacted evidence refs.
3. Gateway publishes `tool.completed.v1`.
4. Memory Knowledge may consume the event or fetch redacted evidence through result API.
5. Raw output does not enter memory directly.

