# Employee Portal — Transactions and Outbox

> **Document ID:** LLD-EP-008
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. Why this document still exists

Domain 09/10 hold no database transaction and have no outbox table — both are backend persistence-layer concepts. This document position is kept to clearly answer a real question that will be asked: "How do we guarantee that a single send, disrupted by a network blip, doesn't turn into two real backend side effects?" — the answer is not "the frontend builds its own transaction," but **relying entirely on the backend's existing idempotency mechanism**; this document states that dependency clearly.

## 2. How the frontend "borrows" the backend's atomicity guarantee

For every operation with a real side effect (sending a message, confirming a proposal, the ticket-creation fallback path), the frontend's only responsibility is: **generate a stable `Idempotency-Key`, and reuse the exact same key on any retry — never regenerate it.**

```text
User clicks "Send" → generates idempotencyKey = uuid() → stored with this send attempt's local state
  → request fails/times out → retry → reuses the same idempotencyKey
  → request succeeds → idempotencyKey retired; the next message generates a new one
```

The real atomicity (message persisted + agent orchestration triggered + outbox event) happens entirely inside `03-agent-runtime-orchestration` — the frontend doesn't know, and doesn't need to know, how that side draws its transaction boundary. That is precisely the point of layering.

## 3. The one place the frontend must guarantee its own "atomicity": local draft writes

BI-EP-006 requires that expiring a session never loses a draft. This is the one truly "must not lose a write" scenario the frontend owns on its own — using `localStorage.setItem` for a synchronous write (not IndexedDB's async transaction), guaranteeing reliable persistence within the narrow window right before page unload/token expiry. This is not a real "transaction" — it's a reminder to pick the right storage API when implementing this.
