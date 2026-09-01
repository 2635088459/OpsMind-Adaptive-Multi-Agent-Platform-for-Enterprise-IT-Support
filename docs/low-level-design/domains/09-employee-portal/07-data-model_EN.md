# Employee Portal — Data Model

> **Document ID:** LLD-EP-007
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. This domain owns no backend schema

Unlike domains 02-08, each of which owns its own Postgres schema (`ticket.*`/`agent.*`/`memory.*`, see shared baseline §7), employee-portal **has no server-side database**. This document describes the browser's local storage model — a frontend engineering implementation detail, not part of the platform's schema-ownership table.

## 2. Local storage model

### 2.1 IndexedDB — conversation cache
```text
table: conversation_cache
  conversationId: string (PK)
  ticketId: string | null
  messages: Message[]          // see 01-domain-model, stored serialized
  lastSyncedAt: datetime
```
Purpose: keeps the recent conversation history visible offline/on a weak connection; once back online, an incremental sync against server data is performed — the local cache **never** overwrites server state (echoes BI-EP-005).

### 2.2 localStorage — drafts and preferences
```text
key: draft:{conversationId}      → unsent text + pending attachment refs (depended on by BI-EP-006)
key: pref:theme                  → light/dark/system
key: pref:lastActiveConversation → used by UC-EP-06's "resume the last conversation"
```

### 2.3 In-memory state (Zustand store, not persisted)
```text
turnState: TurnState              // see 03-state-machine §3.1
attachmentUploads: Map<id, UploadState>
ticketPanelConnectionState: "CONNECTING" | "LIVE" | "RECONNECTING" | "STALE"
```

## 3. Mapping to backend data (read-only projection, not a locally authoritative copy)

| Frontend type | Source domain | Fetch method |
|---|---|---|
| `TicketStatusView` | 02-ticket-workflow | Initial REST fetch + incremental SSE updates |
| `UserSession` | 01-user-access-authentication | OIDC session (cookie), never persisted to IndexedDB |
| `ProposedAction`/`EscalationNotice` | 03-agent-runtime-orchestration (to be built) | Returned inline in the conversation-turn response, never fetched separately |

## 4. Data retention and cleanup

- `conversation_cache` retains the 20 most recent active/escalated conversations by conversationId, evicting the oldest by last-active time beyond that (a purely client-side LRU, no backend involvement).
- On logout, all local caches except `draft:*` are cleared (drafts are preserved to help restore state on the same browser's next login for the same account — but note this is that same account's own draft; different users on the same device must be fully isolated, implemented by prefixing storage keys with `subject` to avoid cross-account leakage on a shared device).
