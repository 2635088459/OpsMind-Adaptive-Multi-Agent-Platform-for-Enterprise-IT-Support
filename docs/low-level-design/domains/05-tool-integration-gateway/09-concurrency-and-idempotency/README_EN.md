# 09 Concurrency And Idempotency

## Idempotency Layers

Gateway needs four idempotency layers:

1. API request idempotency: `idempotencyKey + workflowInstanceId + agentTaskId`.
2. Event consumer idempotency: `eventId + consumerName`.
3. Execution attempt idempotency: `toolRequestId + attemptNumber`.
4. Connector side-effect idempotency: `connectorId + operationKey`.

## Tool Request Idempotency

When Runtime retries `POST /tool-requests`:

- Same payload hash: return existing ToolRequest.
- Different payload hash: return `IDEMPOTENCY_CONFLICT`.
- Original request is already final: still return existing final summary and do not re-execute.

## Worker Concurrent Claim

Worker claim rules:

- Claim only `QUEUED` or retry-due requests.
- Use row lock or lease compare-and-set.
- Set `lease_owner` and `lease_expires_at`.
- Other workers may take over after lease expiry.
- Only one active execution may exist for a request at a time.

## Connector Operation Key

Recommended `operationKey` format:

```text
toolRequestId:attemptNumber:connectorId:capabilityName
```

For connectors whose target systems support idempotency, pass the operation key directly.

For connectors without native idempotency:

- mutation operations default to higher risk;
- external lookup metadata must be stored;
- timeout must enter reconciliation;
- high-risk mutation must not be blindly repeated.

## Approval Event Idempotency

Duplicate `approval.granted.v1`:

- If ToolRequest is already `QUEUED`, `EXECUTING`, or final, skip.
- If approval linkage does not match, write security audit and reject.

Duplicate `approval.denied.v1`:

- If request is already final, skip.
- If request already executed, external side effects cannot be rolled back; publish audit discrepancy.

## Outbox Idempotency

Outbox event `eventId` is generated and persisted at insert time. Publisher retries must reuse the same eventId.

Consumers must not rely on broker exactly-once delivery; they must deduplicate by event id.

## Concurrent Cancellation

Cancellation and execution completion may race:

- Completion commits first: cancel returns final completed.
- Cancel commits first and connector has not been called: request enters `CANCELLED`.
- Cancel commits first but connector was called: request enters `CANCEL_REQUESTED` and waits for connector hook/reconciliation.

