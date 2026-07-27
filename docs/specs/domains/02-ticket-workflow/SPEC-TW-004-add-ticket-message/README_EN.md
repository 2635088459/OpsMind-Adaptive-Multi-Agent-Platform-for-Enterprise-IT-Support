# SPEC-TW-004 — Add Ticket Message File Guide

> **Spec ID:** SPEC-TW-004  
> **Phase:** Phase 02 — Ticket Query and Message Slice  
> **API:** `POST /api/v1/tickets/{ticketId}/messages`

## 1. Purpose

This folder defines the complete behavior for Employees and IT Support appending Ticket messages:

```text
Authorization
→ Message Type
→ Visibility
→ Ticket State Guard
→ Idempotency
→ Append-only Persistence
→ Audit
→ Outbox
→ Tests
```

## 2. File Structure

```text
SPEC-TW-004-add-ticket-message/
├── README_CN.md
├── README_EN.md
├── spec_CN.md
├── spec_EN.md
├── acceptance.feature
├── traceability-entry.yaml
├── schemas/
│   ├── employee-add-message-request.schema.json
│   ├── support-add-message-request.schema.json
│   ├── add-message-response.schema.json
│   ├── ticket-message-added-v1.schema.json
│   └── error-envelope.schema.json
└── examples/
    ├── employee-public-message-request.json
    ├── support-public-message-request.json
    ├── support-internal-note-request.json
    ├── add-message-response.json
    ├── ticket-message-added-v1.json
    ├── invalid-message-error.json
    ├── message-not-allowed-error.json
    └── idempotency-key-reused-error.json
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
Message Domain RED
→ Authorization RED
→ State Guard RED
→ Idempotency RED
→ Persistence/Atomicity RED
→ API RED
→ Event Contract RED
→ Minimum Code
→ Redaction
→ Verify
```

## 5. Key Boundaries

- Employees create only `PUBLIC_REQUESTER_MESSAGE`.
- Support creates `PUBLIC_SUPPORT_MESSAGE` or `INTERNAL_SUPPORT_NOTE`.
- Author and visibility are server-derived.
- Messages are append-only; no update or delete.
- A `WAITING_FOR_USER` reply does not auto-transition in Phase 02.
- A `RESOLVED` reply does not auto-reopen.
- `CLOSED` and `CANCELLED` reject new messages.
- Message, Audit, Outbox, and Idempotency commit atomically.
- Events, Audit, logs, and traces exclude full content.

## 6. Code Location

```text
services/ticket-workflow-service/
└── src/main/java/dev/opsmind/ticketworkflow/ticket/
```

## 7. Verification

```bash
./mvnw clean verify
```
