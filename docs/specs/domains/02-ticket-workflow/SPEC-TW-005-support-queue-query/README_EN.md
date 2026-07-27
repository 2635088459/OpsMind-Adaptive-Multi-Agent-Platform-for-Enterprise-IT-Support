# SPEC-TW-005 — Support Queue Query File Guide

> **Spec ID:** SPEC-TW-005  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Feature:** IT Support queries an authorized Ticket Queue  
> **API:** `GET /api/v1/support/tickets`

---

# 1. Purpose

This folder defines the complete Support Queue Query behavior:

```text
Support Authentication
→ Queue Scope Authorization
→ Whitelisted Filters
→ Deterministic Urgency Sort
→ Signed Cursor Pagination
→ Minimal Support Projection
→ Audit / Observability
```

It answers:

- Which Tickets can Support view?
- How are Application, Team, Region, and Tenant scopes pushed into SQL?
- Which states appear in the default Queue?
- How do SLA and Priority determine ordering?
- How does the cursor bind filters, scope, and evaluation time?
- Why does the Queue summary exclude full Description and messages?
- How does the query avoid unbounded reads, Offset, and N+1?

---

# 2. File Structure

```text
SPEC-TW-005-support-queue-query/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── support-queue-response.schema.json
│   ├── support-ticket-summary.schema.json
│   ├── invalid-cursor-error.schema.json
│   ├── forbidden-filter-scope-error.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── default-queue-response.json
    ├── filtered-queue-response.json
    ├── empty-queue-response.json
    ├── invalid-cursor-error.json
    └── forbidden-filter-scope-error.json
```

---

# 3. Review Order

```text
README_CN
→ spec_CN
→ acceptance.feature
→ schemas
→ examples
→ traceability-entry
→ English consistency review
```

---

# 4. Implementation Order

```text
Queue Authorization RED
→ Filter Validation RED
→ Sort Ranking RED
→ Cursor Integrity RED
→ PostgreSQL Projection RED
→ Query Plan RED
→ Controller GREEN
→ Audit / Telemetry
→ Verify
```

---

# 5. Key Boundaries

- Only Support roles call this API.
- `tickets:read:queue` is required.
- Queue scope comes from trusted security context, never client claims.
- Authorization predicates are pushed into SQL.
- The default Queue contains only non-terminal Tickets.
- Default sorting uses SLA urgency, priority, CreatedAt, and TicketId.
- The cursor binds filters, sort, actor scope, and `evaluationTime`.
- The Queue is a live operational view, not a historical export snapshot.
- Responses exclude full Description, message content, internal notes, and secrets.
- No Offset, total count, or unbounded query.
- The query never mutates Tickets or emits Outbox events.

---

# 6. Code Location

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

Recommended classes:

```text
QuerySupportQueueUseCase
QuerySupportQueueApplicationService
SupportQueueQuery
SupportQueueFilters
SupportQueueCursor
SupportQueueScope
SupportTicketSummary
JdbcSupportQueueQueryAdapter
SupportQueueCursorCodec
SupportTicketQueryController
```

---

# 7. Verification

```bash
./mvnw clean verify
```

Queue authorization, filter scope, cursor integrity, SLA evaluation time, stable sort, PostgreSQL projection, query plan, field visibility, telemetry redaction, ArchUnit, and CI tests must pass.
