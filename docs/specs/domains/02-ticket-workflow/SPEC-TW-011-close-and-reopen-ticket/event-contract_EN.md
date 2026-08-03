# SPEC-TW-011 — Event Contract

## 1. Event Types

```text
ticket.closed.v1
ticket.reopened.v1
```

Events are published only from the transactional outbox after the business transaction commits.

## 2. ticket.closed.v1

Payload example:

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "resolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "previousStatus": "RESOLVED",
  "newStatus": "CLOSED",
  "closeReasonCode": "REQUESTER_CONFIRMED",
  "closedBy": "sam.support",
  "closedAt": "2026-07-31T20:10:00Z"
}
```

## 3. ticket.reopened.v1

Payload example:

```json
{
  "supportQueueId": "9d38b723-4a4d-47d3-94fe-32ef78cc0690",
  "assigneeId": "sam.support",
  "previousResolutionCycleId": "4bde946d-60b8-4e4e-9970-6a0d0d1448f1",
  "newResolutionCycleId": "b2b0eb44-aecf-4e4d-a77a-2b09d9eab2e8",
  "previousStatus": "CLOSED",
  "newStatus": "IN_PROGRESS",
  "reopenReasonCode": "ISSUE_RECURRED",
  "reopenCount": 1,
  "reopenedBy": "sam.support",
  "reopenedAt": "2026-07-31T21:30:00Z",
  "ownershipStatus": "ACTIVE"
}
```

## 4. Envelope

Use the Phase 03 event envelope:

- `eventId`
- `eventType`
- `occurredAt`
- `aggregateType = Ticket`
- `aggregateId`
- `aggregateVersion`
- `correlationId`
- `causationId`
- `actor`
- `data`

The current codebase has no tenant concept. Unless tenant is introduced globally, these events do not require `tenantId`.

## 5. Delivery Semantics

- at-least-once delivery;
- `eventId` is the consumer deduplication key;
- partition key is `aggregateId`;
- ordering is interpreted through `aggregateVersion`;
- breaking changes require v2.

## 6. Privacy

Events must not contain tokens, email, raw claims, full reason text, private messages, approval details, tool logs, or full identity profiles. Reason text may enter timeline only as a sanitized short summary and never as a metric label.
