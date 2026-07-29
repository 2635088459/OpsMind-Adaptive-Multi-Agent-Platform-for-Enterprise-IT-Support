# SPEC-TW-007 — Event, Timeline, and Audit Contract

## 1. Domain Event

```text
ticket.triaged.v1
```

The aggregate type is `TICKET`. The outbox row is inserted in the same transaction as the ticket update. Publishing is at least once; consumers must deduplicate by `eventId`.

## 2. Event Envelope

```json
{
  "eventId": "55555555-5555-5555-5555-555555555555",
  "eventType": "ticket.triaged.v1",
  "eventVersion": 1,
  "occurredAt": "2026-07-29T18:30:00Z",
  "producer": "ticket-workflow-service",
  "tenantId": "66666666-6666-6666-6666-666666666666",
  "correlationId": "21ae628b-f15d-47d1-a937-1be0f85d4cd1",
  "causationId": "2df4faae-9862-4ee6-bca0-a3b8a3455aa0",
  "actor": {
    "actorId": "44444444-4444-4444-4444-444444444444",
    "actorType": "USER"
  },
  "data": {
    "ticketId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "fromStatus": "OPEN",
    "toStatus": "TRIAGED",
    "categoryId": "11111111-1111-1111-1111-111111111111",
    "subcategoryId": "22222222-2222-2222-2222-222222222222",
    "priority": "HIGH",
    "supportQueueId": "33333333-3333-3333-3333-333333333333",
    "ticketVersion": 8
  }
}
```

## 3. Schema Rules

- UUIDs are strings using canonical UUID form;
- timestamps are UTC RFC 3339;
- `eventVersion` is an integer and starts at `1`;
- `subcategoryId` may be `null`;
- `actorType` is `USER`, `SERVICE`, or `SYSTEM`;
- fields are additive within v1; breaking changes require a new event version/name;
- requester message bodies, tokens, and secrets are excluded.

## 4. Outbox Metadata

Recommended values:

| Field | Value |
|---|---|
| aggregate type | `TICKET` |
| aggregate ID | ticket ID |
| event type | `ticket.triaged.v1` |
| partition key | ticket ID |
| content type | `application/json` |
| status | `PENDING` |

Ordering is required per ticket, not globally.

## 5. Timeline Entry

```json
{
  "type": "TICKET_TRIAGED",
  "visibility": "INTERNAL",
  "actorId": "44444444-4444-4444-4444-444444444444",
  "occurredAt": "2026-07-29T18:30:00Z",
  "summary": "Ticket triaged to Network Support with HIGH priority.",
  "metadata": {
    "fromStatus": "OPEN",
    "toStatus": "TRIAGED",
    "categoryId": "11111111-1111-1111-1111-111111111111",
    "subcategoryId": "22222222-2222-2222-2222-222222222222",
    "priority": "HIGH",
    "supportQueueId": "33333333-3333-3333-3333-333333333333"
  }
}
```

Timeline summary generation must be deterministic or use stored catalog display names captured safely at write time. It must not depend on an LLM.

## 6. Audit Record

Audit action: `ticket.triage`.

Approved before/after fields:

```text
status
categoryId
subcategoryId
priority
supportQueueId
triagedBy
triagedAt
version
```

The audit record also stores actor, tenant, correlation ID, source identity type, and result. The raw Authorization header and full request body are never stored.

## 7. Consumer Expectations

Consumers may update search, analytics, notification, or SLA projections later, but consumer failure cannot roll back the committed triage. Consumers must:

- deduplicate using `eventId`;
- tolerate additive fields;
- preserve per-ticket ordering;
- route unsupported event versions to operational handling instead of silently misreading them.

