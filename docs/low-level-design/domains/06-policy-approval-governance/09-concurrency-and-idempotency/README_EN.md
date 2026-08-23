# 09 Concurrency And Idempotency

## Idempotency Keys

- Policy evaluation: `decisionKey + inputHash`.
- Approval request: `sourceDomain + sourceRequestId + requestKey`.
- Approval command: `approvalRequestId + commandIdempotencyKey`.
- Event consumer: `eventId + consumerName`.

## Concurrent Approval

When multiple approvers act on the same request:

- the first transaction that commits a final decision succeeds;
- later requests return the existing final decision;
- conflicting payload returns conflict and writes audit.

## Policy Version Race

Policy evaluation must bind the effective policy version chosen at evaluation start. If a new version is published during evaluation, this decision still uses the original version.

## Duplicate Decision

Same input hash returns the existing decision. Same decisionKey with different input hash returns conflict, preventing downstream from overwriting different facts using one business key.

## Duplicate Approval Events

Downstream must deduplicate repeated `approval.granted.v1` by event id and `approvalRequestId + sourceRequestId + requestHash`.

