# SPEC-TW-008 — Event Contract

## 1. Event Names

```text
ticket.assigned.v1
ticket.reassigned.v1
ticket.unassigned.v1
```

They are integration events published from the transactional outbox only after the database transaction commits.

## 2. Common Envelope

```json
{
  "eventId": "a1049608-564e-4600-8b32-5134da28d8e0",
  "eventType": "ticket.assigned.v1",
  "occurredAt": "2026-07-29T19:15:00Z",
  "tenantId": "32a464b7-57f8-43bd-be7e-6ebaa041c730",
  "aggregateType": "Ticket",
  "aggregateId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "aggregateVersion": 13,
  "correlationId": "b2a09295-5b64-4d28-8d40-ac36c7f46aec",
  "causationId": "8d79d912-4550-4ced-a3ed-f09c2400f05f",
  "actor": {
    "type": "USER",
    "id": "2ec23fb6-0e09-42d1-82aa-dda587bfa912"
  },
  "data": {}
}
```

## 3. Assigned Data

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "previousStatus": "TRIAGED",
  "newStatus": "ASSIGNED",
  "reason": "Primary endpoint support owner"
}
```

## 4. Reassigned Data

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "previousAssigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "assigneeId": "98bf86d3-d709-448b-acd9-ef9ecbbc3d23",
  "status": "IN_PROGRESS",
  "reason": "Escalated to network specialist"
}
```

## 5. Unassigned Data

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "previousAssigneeId": "17cb78fb-c36d-4bb2-9687-84d86d726192",
  "previousStatus": "ASSIGNED",
  "newStatus": "TRIAGED",
  "reason": "Agent left the support rotation"
}
```

## 6. Delivery Semantics

- at-least-once delivery;
- `eventId` is the consumer deduplication key;
- partition key is `aggregateId`;
- ordering is interpreted by `aggregateVersion`;
- producers never publish directly inside the business transaction;
- schema evolution is additive within v1; breaking changes require v2.

## 7. Privacy and Security

Events must not include tokens, email addresses, raw claims, private ticket messages, queue membership proofs, or entire identity profiles. Consumers resolve display information through authorized sources.

## 8. Consumer Expectations

Consumers must be idempotent, ignore already-processed `eventId` values, reject malformed events to their DLQ policy, and avoid applying an older aggregate version over a newer projection.

## 9. Observability

Outbox and publisher telemetry includes event type, event ID, aggregate ID/version, correlation ID, attempt count, and outcome. It excludes event secrets and full reason text from metric labels.
