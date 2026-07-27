# SPEC-TW-003 — List Requester Tickets File Guide

> **Spec ID:** SPEC-TW-003  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **API:** `GET /api/v1/tickets`

## 1. Purpose

This folder defines the complete behavior for an Employee listing Tickets they created, including ownership, filters, stable sorting, cursor pagination, cursor integrity, response schemas, tests, and traceability.

## 2. File Structure

```text
SPEC-TW-003-list-requester-tickets/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── requester-ticket-list-response.schema.json
│   ├── ticket-summary.schema.json
│   ├── invalid-cursor-error.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── first-page-response.json
    ├── next-page-response.json
    ├── empty-list-response.json
    ├── filtered-list-response.json
    └── invalid-cursor-error.json
```

## 3. Review Order

```text
README_CN
→ spec_CN
→ acceptance.feature
→ schemas
→ examples
→ traceability-entry
→ English consistency review
```

## 4. Implementation Order

```text
Ownership Test
→ Filter Validation Test
→ Cursor Codec Test
→ Stable Sort Test
→ PostgreSQL Projection
→ Controller
→ Telemetry
→ Traceability
```

## 5. Key Boundary

- SQL includes `requester_id = principal.subject`.
- Default order is `createdAt DESC, ticketId DESC`.
- Use keyset pagination, not offset pagination.
- Responses exclude full Description, RequesterId, and internal fields.
- Queries never mutate Tickets or emit Outbox events.

## 6. Code Location

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

Recommended classes:

```text
ListRequesterTicketsUseCase
ListRequesterTicketsApplicationService
RequesterTicketQueryPort
JdbcRequesterTicketQueryAdapter
TicketListCursorCodec
PublicTicketQueryController
```

## 7. Verification

```bash
./mvnw clean verify
```

Cursor, ownership, pagination, schema, PostgreSQL, ArchUnit, and CI tests must pass.
