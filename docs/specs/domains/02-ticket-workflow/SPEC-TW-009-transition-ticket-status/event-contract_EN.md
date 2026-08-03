# SPEC-TW-009 — Event Contract

## 1. Event Name

```text
ticket.status-changed.v1
```

The event is published from the transactional outbox only after the database transaction commits.

## 2. Common Envelope

```json
{
  "eventId": "a1049608-564e-4600-8b32-5134da28d8e0",
  "eventType": "ticket.status-changed.v1",
  "occurredAt": "2026-07-31T18:35:00Z",
  "aggregateType": "Ticket",
  "aggregateId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "aggregateVersion": 14,
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec",
  "causationId": "8d79d912-4550-4ced-a3ed-f09c2400f05f",
  "actor": {
    "type": "IT_SUPPORT",
    "id": "sam.support"
  },
  "data": {}
}
```

This codebase currently has no tenant concept; this event does not require `tenantId` unless tenancy is introduced globally later.

## 3. Data Payload

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "previousStatus": "ASSIGNED",
  "newStatus": "IN_PROGRESS",
  "transitionId": "SM-005",
  "reasonCode": "WORK_STARTED",
  "reason": "Starting endpoint investigation",
  "waitingForRequesterSince": null,
  "approvalReference": null
}
```

## 4. Waiting Payload Rules

- `WAITING_FOR_USER`: `waitingForRequesterSince` is non-null and `approvalReference` is null.
- `WAITING_FOR_APPROVAL`: `approvalReference` is non-null and `waitingForRequesterSince` is null.
- `IN_PROGRESS`: both waiting metadata fields are null.

## 5. Delivery Semantics

- at-least-once delivery;
- `eventId` is the consumer deduplication key;
- partition key is `aggregateId`;
- ordering is interpreted by `aggregateVersion`;
- schema evolution is additive within v1; breaking changes require v2.

## 6. Privacy and Security

Events must not include tokens, email addresses, raw claims, private ticket messages, approval body details, queue membership proofs, or full identity profiles.

## 7. Consumer Expectations

Consumers must be idempotent, ignore already-processed `eventId` values, route malformed events through their DLQ policy, and avoid applying an older aggregate version over a newer projection.

## 8. Observability

Outbox and publisher telemetry includes event type, event ID, aggregate ID/version, correlation ID, attempt count, and outcome. Metric labels exclude reason, actor ID, and idempotency key.
