# 10 Failure Handling

## Failure Classes

### Validation Failure

Invalid request schema, capability, actor, or scope. ToolRequest enters `REJECTED`; connector is not executed.

### Policy / Approval Failure

Policy denied or approval denied. Gateway publishes final `tool.completed.v1` with status `POLICY_DENIED` or `APPROVAL_DENIED`.

### Connector Retryable Failure

Network error, 429, temporary 5xx, or transient dependency outage. Reschedule according to retry policy.

### Connector Non-Retryable Failure

Insufficient permission, invalid input, or missing target resource. Move to `TERMINAL_FAILED`.

### Timeout / Unknown Outcome

If connector call times out but the external system may already have performed the action, mutation must not be retried blindly. It must enter `RECONCILING`.

### Partial Side Effect

External system partially succeeded, for example creating a resource but failing to update labels. Gateway must persist partial metadata and publish explicit status.

## Reconciliation

Reconciliation worker uses connector-specific status lookup:

- query external system by `operationKey`;
- inspect external resource refs;
- compare expected output;
- decide `SUCCEEDED`, `FAILED`, or `UNCERTAIN`.

If the result remains `UNCERTAIN` for too long, Gateway publishes final uncertain result and marks human handling required.

## Poison Request

The following enter poison handling:

- the same request repeatedly triggers unparseable connector error;
- result normalization always fails;
- connector manifest is incompatible with actual output schema;
- outbox publication fails beyond threshold.

Poison requests are not executed automatically again and require admin audit.

## Gateway Crash Recovery

Startup recovery:

1. Replay pending outbox.
2. Scan lease-expired executions.
3. Move `INVOKING` executions with expired lease to reconciliation.
4. Restore scheduling for `QUEUED` requests.
5. Keep `WAITING_APPROVAL` requests waiting for approval event or timeout handling.

## Connector Crash Or Unavailability

- Consecutive failures move an `ACTIVE` connector to `DEGRADED`.
- Health check failures beyond threshold move it to `DISABLED`.
- Queued requests need connector reselection or terminal failure.
- High-risk mutation must not switch connectors automatically unless policy allows it.

