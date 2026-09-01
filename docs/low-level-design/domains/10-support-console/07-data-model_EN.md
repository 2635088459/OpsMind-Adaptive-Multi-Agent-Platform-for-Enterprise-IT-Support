# Support Console — Data Model

> **Document ID:** LLD-SC-007
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. This domain likewise owns no backend schema

Same principle as domain 09 — every business fact lives in domains 02/05/06/07's own databases; support-console only holds client-side display state.

## 2. Local storage model

### 2.1 In-memory state (Zustand, not persisted)
```text
selectedTicketId: string | null
queuePollingState: "LOADING" | "LIVE_POLLING" | "DEGRADED"
ticketDetailState: "UNSELECTED" | "LOADING_DETAIL" | "READY" | "PARTIAL"
```

### 2.2 sessionStorage — agent UI preferences (not persisted across sessions)
```text
key: pref:selectedQueueId      // the last-viewed queue, restored on page refresh
key: pref:sortOrder            // queue sort order
```

Deliberately using `sessionStorage` rather than `localStorage`: an agent's UI preferences only need to persist within the current work session, not across days (unlike domain 09's draft scenario, which genuinely must survive across sessions — the two are different in kind).

### 2.3 TanStack Query cache (server data, not a locally authoritative copy)
`QueueView`, `TicketDetailView`, `ApprovalRequestView` are all managed through TanStack Query, following the same principle as domain 09: the frontend cache is a display optimization only, never the source of truth.

## 3. Shared type source with domain 09

`TicketStatusView`/`RiskLevel`/`TicketStatus` and other types are shared between the two domains via a single definition in `packages/api-contracts` (see `01-domain-model` §4) — not redeclared here.
