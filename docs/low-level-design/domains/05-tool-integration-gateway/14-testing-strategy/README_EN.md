# 14 Testing Strategy

## Test Goals

Tests must prove:

- Agents cannot bypass Gateway and execute tools directly.
- Request, approval, execution, and result publication are idempotent end to end.
- External side effects do not repeat because of retries.
- Credentials do not leak into logs, events, results, or memory.
- Gateway/worker/broker crashes are recoverable.

## Unit Tests

Cover:

- ToolRequest state transitions;
- Execution Attempt state transitions;
- idempotency conflict;
- connector selection;
- approval required decision;
- retry policy;
- result normalization;
- redaction metadata;
- audit record generation.

## Integration Tests

Use PostgreSQL + RabbitMQ/testcontainers to cover:

- request creation writes outbox;
- outbox publishes event;
- approval granted moves request to queued;
- worker claim plus fake connector execution;
- tool.completed is published;
- duplicate API request does not duplicate execution;
- worker takeover after lease expiry;
- processed event deduplication.

## Connector Contract Tests

Every connector must pass contracts:

- valid manifest schema;
- input schema validation;
- output schema normalization;
- timeout behavior;
- retryable/non-retryable error mapping;
- reconcile/cancel hook behavior;
- no secret in output/log.

## Security Tests

Cover:

- Agent cannot read credential/vault ref;
- raw output API returns forbidden without permission;
- redaction failure prevents publishing raw content;
- network policy denies undeclared endpoint;
- approval-required capability cannot execute without approval.

## Recovery Tests

Cover:

- worker crashes before connector invocation;
- worker crashes after connector invocation but before saving result;
- outbox publish succeeds but process crashes before ack;
- approval event delivered repeatedly;
- reconciliation after timeout succeeds/fails/remains uncertain.

## Cross-Domain Contract Tests

Must verify with 03:

- `POST /tool-requests` request/response schema;
- `tool.completed.v1` payload;
- duplicate `tool.completed.v1` does not complete AgentTask twice.

Must verify with 04:

- tool evidence refs can be consumed by memory ingestion;
- raw output does not enter memory.

Must verify with 06:

- `tool.approval.required.v1`;
- `approval.granted.v1` / `approval.denied.v1`.

## Acceptance Criteria

Before 05 LLD moves into phase/spec, it should have:

- all 14 LLD slices complete;
- API/event/data model traceable to 03/04/06;
- every state machine has final states and failure paths;
- every external side effect has idempotency and recovery strategy;
- testing strategy covers security, recovery, and cross-domain contracts.

