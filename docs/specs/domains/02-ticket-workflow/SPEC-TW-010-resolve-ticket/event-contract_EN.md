# SPEC-TW-010 — Event Contract

## 1. Event Name

```text
ticket.resolved.v1
```

The event is published from the transactional outbox only after the database transaction commits.

## 2. Common Envelope

```json
{
  "eventId": "a1049608-564e-4600-8b32-5134da28d8e0",
  "eventType": "ticket.resolved.v1",
  "occurredAt": "2026-07-31T19:05:00Z",
  "aggregateType": "Ticket",
  "aggregateId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "aggregateVersion": 18,
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
  "resolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "previousStatus": "IN_PROGRESS",
  "newStatus": "RESOLVED",
  "resolutionCode": "FIXED",
  "resolutionSummary": "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
  "resolvedBy": "sam.support",
  "resolvedAt": "2026-07-31T19:05:00Z"
}
```

## 4. Delivery Semantics

- at-least-once delivery;
- `eventId` is the consumer deduplication key;
- partition key is `aggregateId`;
- ordering is interpreted by `aggregateVersion`;
- schema evolution is additive within v1; breaking changes require v2.

## 5. Privacy and Security

Events must not include tokens, email addresses, raw claims, private ticket messages, approval details, tool execution logs, queue membership proofs, or full identity profiles.

`resolutionSummary` may contain requester-facing resolution text, but must not contain secrets, passwords, access tokens, private keys, or full logs.

## 6. Consumer Expectations

Consumers must be idempotent, ignore already-processed `eventId` values, route malformed events through their DLQ policy, and avoid applying an older aggregate version over a newer projection.

## 7. Observability

Outbox and publisher telemetry includes event type, event ID, aggregate ID/version, correlation ID, attempt count, and outcome. Metric labels exclude summary, actor ID, and idempotency key.
