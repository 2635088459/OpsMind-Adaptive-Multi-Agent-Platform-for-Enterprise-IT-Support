# Employee Portal — Concurrency and Idempotency

> **Document ID:** LLD-EP-009
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. Scenario one: duplicate submission (network retry)

**Problem:** The user clicks send, the network blips, and the frontend auto-retries; if the backend's acknowledgement of the first request never came back, the same message could be processed twice.

**Solution:** See `08-transaction-and-outbox` §2 — every retry of the same user action shares one `Idempotency-Key`; at the UI level the send button is disabled while `SENDING`, preventing "human duplication" from repeated clicks (a second line of defense on top of the idempotency key, purely at the UX layer — it does not depend on, nor substitute for, the key).

## 2. Scenario two: concurrent operations on the same conversation across multiple tabs/devices

**Problem:** The same employee has the same conversationId open in two browser tabs, both waiting on/operating the conversation at once.

**Solution (MVP):** No real-time multi-client sync (non-goal, see the roadmap). Simple rule: **the last tab to write successfully wins**; other tabs pick up the latest state on their next fetch/SSE update and overwrite their local view. No conflict-detection dialog, no locking — a reasonable simplification for a self-service employee scenario (contrast with support-console's multi-agent collaboration scenario, whose concurrency requirements are entirely different, see domain 10's own LLD).

## 3. Scenario three: a ProposedAction confirmed twice

**Problem:** The same `actionId` is confirmed twice (e.g. the user clicked confirm, the page stuttered and they clicked again; or two tabs both confirm the same proposal).

**Solution:** `confirm`/`decline` requests also carry an `Idempotency-Key`; additionally, the `actionId` itself is only ever consumable once server-side — a second confirmation request should get back "this proposal has already been handled, current status: {executing/done/declined}" rather than re-triggering real execution. On receiving such a response, the frontend simply refreshes the UI with the returned current status, rather than erroring and blocking the user.

## 4. Scenario four: reordering/duplication caused by an SSE reconnect

**Problem:** After the ticket status panel reconnects, it may receive already-processed old events, or events out of order.

**Solution:** Resume via `Last-Event-ID` (already required by shared baseline §4); the frontend additionally keeps a "latest processed `updatedAt`" cursor — any incremental event whose `updatedAt` is not later than the cursor is discarded outright, never re-rendered, and never regresses the state-machine step highlight.

## 5. Concurrency controls explicitly not built (MVP non-goal)

- No explicit optimistic-lock version number exposed at the frontend layer (the backend `02-ticket-workflow` handles its own optimistic locking internally; the frontend does not need to be aware of it, nor does the UI require the user to resolve a "version conflict" — a backend concept).
- No collaborative-editing-style real-time cursor/typing indicators (that's something support-console's multi-agent collaboration might need, not this self-service employee portal's scenario).
