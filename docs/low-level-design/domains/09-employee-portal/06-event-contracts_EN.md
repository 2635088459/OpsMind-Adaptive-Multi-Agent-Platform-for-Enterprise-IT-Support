# Employee Portal — Event Contracts

> **Document ID:** LLD-EP-006
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. This domain publishes no RabbitMQ events

employee-portal is a pure frontend application running in the browser — it does not connect to RabbitMQ (the backend's own inter-service event bus, see shared baseline §8), nor does it own an outbox. This document still exists (rather than being skipped) to clearly define the two kinds of real-time streams the frontend **consumes**, avoiding confusion with what "event contracts" means in other domains.

## 2. Consumed real-time streams (SSE, not RabbitMQ events)

### 2.1 Ticket status change stream
```text
GET /api/v1/tickets/{ticketId}/events
event: ticket.status.changed
data: {"ticketId","status","updatedAt"}
```
Semantically corresponds to a real internal state transition inside `02-ticket-workflow` (its `TicketStatusChanged` domain event), but this is **not** a direct RabbitMQ subscription — `02-ticket-workflow` itself needs a new gateway layer that forwards internal state changes into SSE (that forwarding is that domain's own implementation detail; this document does not overreach into designing it).

### 2.2 Streaming conversation-turn responses (optional, phase 2+)
The MVP's `05-api-contracts` §2.2 `POST .../messages` is a synchronous request-response. If a later "typewriter effect" streaming experience is wanted, it would add:
```text
POST /api/v1/conversations/{id}/messages/stream
Accept: text/event-stream
event: token
data: {"text": "Look"}
event: token
data: {"text": "ing"}
...
event: done
data: {"type": "text" | "proposedAction" | "escalation", ...}
```
Not built this period (MVP) — get the functionality working with a synchronous response first; the streaming typing effect is a phase-2 experience enhancement, explicitly listed as a non-goal (see the roadmap).

## 3. Relationship to the backend's own event envelope

The event envelope backend domains use among themselves (`eventId/eventType/producer/schemaVersion/...`, see shared baseline §8) is **not** passed through to the frontend as-is — the SSE payload is a trimmed shape purpose-built for frontend consumption (the `ticket.status.changed` data has only 3 fields), not a forwarded copy of the internal event envelope. This is a deliberate boundary: the frontend should not, and does not need to, know about internal backend provenance fields like `correlationId`/`causationId`.
