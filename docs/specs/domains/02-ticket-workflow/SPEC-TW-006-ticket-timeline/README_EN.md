# SPEC-TW-006 — Ticket Timeline File Guide

> **Spec ID:** SPEC-TW-006  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **Feature:** View a unified Ticket Timeline under authorization rules  
> **API:** `GET /api/v1/tickets/{ticketId}/timeline`

---

# 1. Purpose

This folder defines the complete Ticket Timeline Query behavior:

```text
Authentication
→ Resource Authorization
→ Actor-specific Visibility
→ Snapshot Boundary
→ Unified Timeline Projection
→ Stable Keyset Pagination
→ Sensitive-read Audit
→ Safe Response
```

The Phase 02 Timeline combines:

```text
Ticket Created
Status History
Public Requester Messages
Public Support Messages
Internal Support Notes
```

It does not expose raw Audit records or complete Approval, Tool, or Verification internals.

---

# 2. File Structure

```text
SPEC-TW-006-ticket-timeline/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── employee-timeline-response.schema.json
│   ├── support-timeline-response.schema.json
│   ├── employee-timeline-item.schema.json
│   ├── support-timeline-item.schema.json
│   ├── invalid-cursor-error.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── employee-timeline-first-page.json
    ├── employee-timeline-next-page.json
    ├── support-timeline-with-internal-note.json
    ├── empty-timeline-response.json
    └── invalid-cursor-error.json
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
Resource Authorization RED
→ Employee Visibility RED
→ Support Internal Visibility RED
→ Timeline Mapping RED
→ Snapshot/Cursor RED
→ PostgreSQL UNION Projection RED
→ Audit Fail-closed RED
→ Controller GREEN
→ Telemetry Redaction
→ Verify
```

---

# 5. Key Boundaries

- Employees view only public items from owned Tickets.
- Support views only authorized Tickets and needs an additional scope for internal notes.
- The server selects the view; clients cannot request a higher-privilege view.
- A fixed `snapshotAt` prevents new items from entering the same cursor session.
- Default ordering is `occurredAt ASC, itemTypeRank ASC, itemId ASC`.
- Cursors bind Ticket, actor, view, scope, snapshot, and sort version.
- Employee timelines exclude author IDs, internal reasons, and internal notes.
- Sensitive Support Timeline reads create Audit when policy requires it.
- Queries never mutate Tickets or emit Outbox events.
- Phase 02 never exposes raw Audit records as Timeline items.

---

# 6. Code Location

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

Recommended classes:

```text
GetTicketTimelineUseCase
GetTicketTimelineApplicationService
TicketTimelineQuery
TicketTimelineCursor
TicketTimelineViewPolicy
TicketTimelineItem
JdbcTicketTimelineQueryAdapter
TicketTimelineCursorCodec
TicketTimelineController
```

---

# 7. Verification

```bash
./mvnw clean verify
```

Ownership, support scope, visibility, snapshot pagination, stable ordering, PostgreSQL projection, required Audit fail-closed, redaction, non-mutation, ArchUnit, and CI tests must pass.
